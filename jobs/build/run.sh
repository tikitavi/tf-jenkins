#!/bin/bash -eE
set -o pipefail

[ "${DEBUG,,}" == "true" ] && set -x

my_file="$(readlink -e "$0")"
my_dir="$(dirname $my_file)"

source "$my_dir/definitions"

# transfer patchsets info into sandbox
if [ -e $WORKSPACE/patchsets-info.json ]; then
  mkdir -p $WORKSPACE/src/tungstenfabric/tf-dev-env/input/
  cp -f $WORKSPACE/patchsets-info.json $WORKSPACE/src/tungstenfabric/tf-dev-env/input/
fi

rsync -a -e "ssh -i $WORKER_SSH_KEY $SSH_OPTIONS" $WORKSPACE/src $IMAGE_SSH_USER@$instance_ip:./

linux_distr=${TARGET_LINUX_DISTR["${ENVIRONMENT_OS}"]}

echo "INFO: Build started"

# build queens for test container always and add OPENSTACK_VERSION if it's different
openstack_versions='queens'
if [[ "$OPENSTACK_VERSION" != 'queens' ]]; then
  openstack_versions+=",$OPENSTACK_VERSION"
fi

res=0
cat <<EOF | ssh -i $WORKER_SSH_KEY $SSH_OPTIONS $IMAGE_SSH_USER@$instance_ip || res=1
[ "${DEBUG,,}" == "true" ] && set -x
export WORKSPACE=\$HOME
export DEBUG=$DEBUG
export PATH=\$PATH:/usr/sbin

# dont setup own registry
export CONTRAIL_DEPLOY_REGISTRY=0

export CONTAINER_REGISTRY=$CONTAINER_REGISTRY
export SITE_MIRROR=$SITE_MIRROR

export OPENSTACK_VERSIONS=$openstack_versions
export CONTRAIL_CONTAINER_TAG=$CONTRAIL_CONTAINER_TAG$TAG_SUFFIX

# to not to bind contrail sources to container
export CONTRAIL_DIR=""

export DEVENV_IMAGE_NAME=$DEVENV_IMAGE_NAME
# devenftag is passed from parent fetch-sources job
export DEVENV_TAG=$DEVENV_TAG
export CONTRAIL_KEEP_LOG_FILES=true

export LINUX_DISTR=$linux_distr
export CONTRAIL_BUILD_FROM_SOURCE=${CONTRAIL_BUILD_FROM_SOURCE}

cd src/tungstenfabric/tf-dev-env

# TODO: use in future generic mirror approach
# Copy yum repos for rhel from host to containers to use local mirrors

case "${ENVIRONMENT_OS}" in
  "rhel7")
    export BASE_EXTRA_RPMS=''
    export RHEL_HOST_REPOS=''
    mkdir -p ./config/etc
    cp -r /etc/yum.repos.d ./config/etc/
    # TODO: now no way to pu gpg keys into containers for repo mirrors
    # disable gpgcheck as keys are not available inside the contianers
    find ./config/etc/yum.repos.d/ -name "*.repo" -exec sed -i 's/^gpgcheck.*/gpgcheck=0/g' {} + ;
    cp \${WORKSPACE}/src/progmaticlab/tf-jenkins/infra/mirrors/mirror-google-chrome.repo ./config/etc/yum.repos.d/
    cp \${WORKSPACE}/src/progmaticlab/tf-jenkins/infra/mirrors/mirror-pip.conf ./config/etc/pip.conf
    ;;
  "centos7")
    # TODO: think how to copy only required repos
    # - host has centos7/epel enabled. but we also need to copy chrome/docker/openstack repos
    # but these repos are not needed for rhel
    mkdir -p ./config/etc/yum.repos.d
    cp \${WORKSPACE}/src/progmaticlab/tf-jenkins/infra/mirrors/mirror-base.repo ./config/etc/yum.repos.d/
    cp \${WORKSPACE}/src/progmaticlab/tf-jenkins/infra/mirrors/mirror-google-chrome.repo ./config/etc/yum.repos.d/
    cp \${WORKSPACE}/src/progmaticlab/tf-jenkins/infra/mirrors/mirror-openstack.repo ./config/etc/yum.repos.d/
    cp \${WORKSPACE}/src/progmaticlab/tf-jenkins/infra/mirrors/mirror-pip.conf ./config/etc/pip.conf
    # copy docker repo to local machine
    sudo cp \${WORKSPACE}/src/progmaticlab/tf-jenkins/infra/mirrors/mirror-docker.repo /etc/yum.repos.d/
    # use root user for because slave is ubuntu but build machine is centos
    # and they have different users
    export DEVENV_USER=root
    ;;
esac

./run.sh build
EOF

if [[ "$res" != '0' ]] ; then
  echo "ERROR: Build failed"
  exit $res
fi
echo "INFO: Build finished successfully"
