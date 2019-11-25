#!/bin/bash -eE
set -o pipefail

[ "${DEBUG,,}" == "true" ] && set -x

my_file="$(readlink -e "$0")"
my_dir="$(dirname $my_file)"

source "$my_dir/definitions"

ENV_FILE="$WORKSPACE/stackrc"
source $ENV_FILE

echo 'Deploy k8s for helm'

ls -l $WORKSPACE
cat *.env
exit 0

rsync -a -e "ssh $SSH_OPTIONS" $WORKSPACE/src $IMAGE_SSH_USER@$instance_ip:./

cat <<EOF
export PATH=\$PATH:/usr/sbin
cd src/tungstenfabric/tf-devstack/helm
ORCHESTRATOR=k8s SKIP_CONTRAIL_DEPLOYMENT=true ./run.sh
EOF | ssh $SSH_OPTIONS -t $IMAGE_SSH_USER@$instance_ip
