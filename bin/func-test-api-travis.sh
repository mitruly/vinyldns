#!/bin/bash
######################################################################
# Copies the contents of `docker` into target/scala-2.12
# to start up dependent services via docker compose.  Once
# dependent services are started up, the fat jar built by sbt assembly
# is loaded into a docker container.  Finally, the func tests run inside
# another docker container
# At the end, we grab all the logs and place them in the target
# directory
######################################################################

DIR=$( cd $(dirname $0) ; pwd -P )
WORK_DIR=$DIR/../target/scala-2.12
mkdir -p $WORK_DIR

echo "Cleaning up unused networks..."
docker network prune -f

echo "Copy all docker to the target directory so we can start up properly and the docker context is small..."
cp -af $DIR/../docker $WORK_DIR/

echo "Copy over the functional tests as well as those that are run in a container..."
mkdir -p $WORK_DIR/functest
rsync -av --exclude='.virtualenv' $DIR/../modules/api/functional_test $WORK_DIR/docker/functest

echo "Copy the vinyldns.jar to the api docker folder so it is in context..."
if [[ ! -f $DIR/../modules/api/target/scala-2.12/vinyldns.jar ]]; then
    echo "vinyldns jar not found, building..."
    cd $DIR/../
    sbt api/clean api/assembly
    cd $DIR
fi
cp -f $DIR/../modules/api/target/scala-2.12/vinyldns.jar $WORK_DIR/docker/api

echo "Retrieving docker-compose version.."
docker-compose --version

echo "Starting docker environment and running func tests..."
docker-compose -f $WORK_DIR/docker/docker-compose-func-test.yml --project-directory $WORK_DIR/docker up -d

if [[ ! -d "$DIR"/../target/pytest_reports ]]; then
    mkdir "$DIR"/../target/pytest_reports
fi

if [[ ! -f "$DIR"/../target/pytest_reports/pytest.xml ]]; then
    touch "$DIR"/../target/pytest_reports/pytest.xml
fi

ls -l $WORK_DIR/docker
ls -l $WORK_DIR/docker/functest
chmod +x $WORK_DIR/docker/functest/run-travis.sh
$WORK_DIR/docker/functest/run-travis.sh
test_result=$?

echo "Listing docker containers..."
docker ps

echo "Grabbing the logs..."

echo "View API logs"
docker logs vinyldns-api

echo "View bind9 logs"
docker logs vinyldns-bind9

docker logs vinyldns-api > $DIR/../target/vinyldns-api.log 2>/dev/null
docker logs vinyldns-bind9 > $DIR/../target/vinyldns-bind9.log 2>/dev/null
docker logs vinyldns-mysql > $DIR/../target/vinyldns-mysql.log 2>/dev/null
docker logs vinyldns-elasticmq > $DIR/../target/vinyldns-elasticmq.log 2>/dev/null
docker logs vinyldns-dynamodb > $DIR/../target/vinyldns-dynamodb.log 2>/dev/null
docker logs vinyldns-functest > $DIR/../target/vinyldns-functest.log 2>/dev/null

echo "Dig bind9 server.."
dig @127.0.0.1 -p19001 dummy.
dig @127.0.0.1 -p19001 ok.

echo "Cleaning up docker containers..."
$DIR/./stop-all-docker-containers.sh

echo "Func tests returned result: ${test_result}"
exit ${test_result}
