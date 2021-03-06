import groovy.json.JsonSlurper
import groovy.json.JsonOutput

// TODO Fill up list of projects that can't be run in concurrent mode
SERIAL_PROJECTS = ['Juniper/contrail-kolla-ansible']

// Function find base build fits to be the base build
// get its base builds list if any, and then iterate over the list
// check if items of the list is still working or SUCCESS or FAILURE.
// If next build is fit to be a base build, the function add its id to BASE_BUILDS_LIST
// return false if base build not found or build_id if foundпше
// save BASE_BUILD_ID_LIST sting including base builds chain like "23,22,20"
def save_base_builds() {
  def builds_map = _prepare_builds_map()

  def current_build_id = env.BUILD_ID.toInteger()
  def base_build_id = null
  for (item in builds_map) {
    build_id = item.key
    build_map = item.value
    // Skip current or later builds
    if (!_is_branch_fit(build_map['branch']) || build_id >= current_build_id)
      continue

    if (build_map['status']) {
      // build has been finished
      if (check_build_is_not_failed(build_id)) {
        // We do not need base build!
        break
      }
      // else just skip the build
    } else {
      // build is running
      // Wait for build chain will be prepared
      def base_chain = _wait_for_chain_calculated(build_id)
      if (base_chain == null) {
        // build fails before can calculate BASE_BUILD_ID_LIST
        continue
      }
      if (base_chain.length() == 0) {
        // base_chain is empty, add the only this build to chain
        base_chain = build_id.toString()
      } else {
        // base_chain is not empty string
        if (!_check_base_chain_is_not_failed(base_chain)) {
          // Some of base builds fails
          continue
        }
        base_chain = "${build_id}," + base_chain
      }
      // We found base build!
      base_build_id = build_id
      break
    }
  }

  if (base_build_id) {
      // We found base build! Save base_chain in global.vars
      sh """#!/bin/bash -e
        echo "export BASE_BUILD_ID_LIST=${base_chain}" >> global.env
      """
    // Add base patchset info to the build
    _save_pachset_info(base_build_id)
  } else {
    // If a base build not found save BASE_BUILD_ID_LIST anyway, because it needs
    // to detect if process of the base build search is finished
    sh """#!/bin/bash -e
      echo "export BASE_BUILD_ID_LIST=" >> global.env
    """
  }

  archiveArtifacts(artifacts: 'global.env')

  return base_build_id
}

// Function find build with build_id and takes its global env.
// Read global.env and wait until variable BASE_BUILD_ID_LIST will be added there
// or if build failed.
// return value of BASE_BUILD_ID_LIST if it was found or null if build fails
def _wait_for_chain_calculated(build_id) {
  def base_id_list = null
  waitUntil {
    def build = _get_build_by_id(build_id)
    base_id_list = _find_base_list(build)
    if (build.getResult() != null && base_id_list == null) {
      // build finishes but no base_id_list was found
      return true
    }
    // returns boolean explicetly
    return base_id_list != null
  }

  return base_id_list
}

// Function get global.env artifact and find there BASE_BUILD_ID_LIST
// Return value of BASE_BUILD_ID_LIST of has been found
// Otherwise return null
def _find_base_list(build) {
  def artifactManager = build.getArtifactManager()
  if (!artifactManager.root().isDirectory())
    return base_id_list

  for (file in artifactManager.root().list()) {
    if (!file.toString().contains('global.env'))
      continue

    // extract global.env artifact for each build if exists
    def fileText = it.open().getText()
    // Check if BASE_BUILD_ID_LIST exists in global.env file
    def line = fileText.readLines().find() { item -> item.startsWith('BASE_BUILD_ID_LIST=') }
    if (!line)
      continue

    return line.split('=')[1].trim()
  }

  return null
}

// Function find the build with build_id and wait it finishes with any result
def wait_pipeline_finished(build_id) {
  waitUntil {
    // Put all this staff in separate function due to Serialisation under waitUntil
    return get_build_result_by_id(build_id) != null
  }
}

// Function check build using build_id is failed or not
def check_build_is_not_failed(build_id) {
  def build = _get_build_by_id(build_id)
  return build.getResult() == null || _is_build_successed(build)
}

def get_build_result_by_id(build_id) {
  def build = _get_build_by_id(build_id)
  // TODO: think about cases when build is null
  def result = build.getResult()
  return result ? result.toString() : null
}

// find and return build of gate pipeline using build_id
// otherwise return null
def _get_build_by_id(build_id) {
  return Jenkins.getInstanceOrNull().getItem(env.JOB_NAME).getBuildByNumber(build_id)
}

// Function parse base chain and check if all builds is not failed
// if function meet successfully finished build in the chain, this
// build and all its base builds remove from the chain (chain shortened)
// Return true if it does NOT found failure build and chain
// and false if it founds some failures
def _check_base_chain_is_not_failed(base_chain) {
  if (base_chain.length() == 0)
    return true
  for (build_id in base_chain.split(",")) {
    // is not finished yes - skip the build
    if (get_build_result_by_id(build_id) != null && !check_build_is_not_failed(build_id))
      return false
  }

  return true
}

// Function return ordered map with builds with data needed for find
// and process base build
def _prepare_builds_map() {
  def gate_pipeline = Jenkins.getInstanceOrNull().getItem(env.JOB_NAME)
  def builds_map = [:]

  for (def build in gate_pipeline.builds) {
    if (!build || !build.getId())
      continue
    def build_id = build.getId().toInteger()
    def result = build.getResult()
    def build_env = build.getEnvironment()

    builds_map[build_id] = [
      'status' : result ? result.toString() : null ,
      'branch' : build_env['GERRIT_BRANCH'],
      'project' : build_env['GERRIT_PROJECT']
    ]
  }

  return builds_map
}

// function return true if project can be run concurrently -
// it has contrail releases/branches structure
// otherwise it returns false
def is_concurrent_project() {
  // TODO: think about checking for concurrent project in patchsets_info
  return !SERIAL_PROJECTS.contains(env.GERRIT_PROJECT)
}

// Function check if build's branch fit to current project branch
// Return true if we can use this build as a base build for current running pipeline
// Otherwise return false
def _is_branch_fit(def branch) {
  if (is_concurrent_project()) {
    // Project has contrail releases structure
    // Branch of the base build must be the same
    if (branch != env.GERRIT_BRANCH)
      return false
  } else {
    // Project has its own releases structure
    // Branch of the base build must be master
    if (branch != 'master')
      return false
  }

  return true
}

// The function get build's artifacts, find there VERIFIED,
// and check if it is integer and more than 0 return SUCCESS
// and return FAILRUE in another case
// !!! Works only if build has been finished! Check getResult() before call this function
def _is_build_successed(build) {
  def artifactManager =  build.getArtifactManager()
  if (!artifactManager.root().isDirectory())
    return false

  for (file in artifactManager.root().list()) {
    if (!file.toString().contains('global.env'))
      continue
    // extract global.env artifact for each build if exists
    def fileText = file.open().getText()
    def line = fileText.readLines().find() { item -> item.startsWith('VERIFIED=') }
    if (!line)
      return false
    def verified = line.split('=')[1].trim().toInteger()
    return verified > 0
  }

  return false
}

// Check if pipeline with the same GERRIT_PROJECT is running
// and if it is then wait until finishes
def wait_until_project_pipeline() {
  def builds_map = _prepare_builds_map()
  def same_project_build = null
  builds_map.any { build_id, build_map ->
    if (build_map['project'] == env.GERRIT_PROJECT && !build_map['status'] &&
        build_id < env.BUILD_ID.toInteger()) {
      same_project_build = build_id
      return true
    }
  }

  if (same_project_build) {
    waitUntil {
      return get_build_result_by_id(same_project_build) != null
    }
  }
}

// function read patchset_info artiface from base build if exists
// read pachset_info of current build
// union all patchset_info in one array
// and write all info to patchset_info artifact of corrent build
def _save_pachset_info(base_build_id) {
  if (!base_build_id || !base_build_id.isInteger())
    return
  def res_json = get_result_patchset(base_build_id)
  if (!res_json)
    return false
  writeFile(file: 'patchsets-info.json', text: res_json)
  archiveArtifacts(artifacts: "patchsets-info.json")
}

// all JSON calsulate to separate function
def get_result_patchset(base_build_id) {
  def new_patchset_info_text = readFile("patchsets-info.json")
  def sl = new JsonSlurper()
  def new_patchset_info = sl.parseText(new_patchset_info_text)
  def base_patchset_info = ""
  // Read patchsets-info from base build
  def base_build = _get_build_by_id(base_build_id)
  def artifactManager =  base_build.getArtifactManager()
  if (artifactManager.root().isDirectory()) {
    def fileList = artifactManager.root().list()
    fileList.any {
      def file = it
      if (file.toString().contains('patchsets-info.json')) {
        // extract global.env artifact for each build if exists
        base_patchset_info = it.open().getText()
        return true
      }
    }
  }
  def sl2 = new JsonSlurper()
  def old_patchset_info = sl2.parseText(base_patchset_info)
  if (old_patchset_info instanceof java.util.ArrayList ) {
    def result_patchset_info = old_patchset_info + new_patchset_info
    def json_result_patchset_info = JsonOutput.toJson(result_patchset_info)
    return json_result_patchset_info
  }
  return false
}

// Function read global.env line by len and if met the line
// with BASE_BUILD_ID_LIST remove it from file
def cleanup_globalenv_vars() {
  def new_patchset_info_text = readFile("global.env")
  new_patchset_info_text.eachLine { line ->
    if (! line.contains('BASE_BUILD_ID_LIST')) {
      sh """#!/bin/bash -e
          echo "${line}" >> global.env
        """
    }
  }

  archiveArtifacts(artifacts: 'global.env')
}

return this