#!/bin/bash -x

# ---------------------------------------------------------------------

trap onexit 1 2 3 15
function onexit() {
    local exit_status=${1:-$?}
    for SITE_HOST in ${SITE_HOSTS[@]}; do
        ssh $SITE_HOST "pkill -f hstore.tag"
    done
    exit $exit_status
}

# ---------------------------------------------------------------------

SITE_HOSTS=( \
    "modis"
    "modis2"
    "vise5"
)
CLIENT_HOSTS=( \
    "saw" \
#     "saw" \
#     "saw" \
)

BASE_CLIENT_THREADS=1
BASE_SITE_MEMORY=2048
BASE_SITE_MEMORY_PER_PARTITION=1024
BASE_PROJECT="tpcc"
BASE_DIR=`pwd`

MARKOV_ENABLE=true
MARKOV_FIXED=true
MARKOV_DIR="files/markovs/vldb-august2012"
MARKOV_RECOMPUTE=true

BASE_ARGS=( \
    "-Dsite.status_enable=false" \
    
    # SITE DEBUG
#     "-Dsite.status_enable=true" \
#     "-Dsite.status_interval=10000" \
#     "-Dsite.status_exec_info=true" \
#     "-Dsite.exec_profiling=true" \
#     "-Dsite.network_profiling=false" \
#     "-Dsite.log_backup=true"\
    
    # Site Params
    "-Dsite.jvm_asserts=false" \
    "-Dsite.cpu_affinity_one_partition_per_core=true" \
    "-Dsite.exec_preprocessing_threads=false" \
    "-Dsite.exec_preprocessing_threads_count=2" \
    "-Dsite.exec_postprocessing_threads=false" \
    "-Dsite.queue_incoming_max_per_partition=10000" \
    "-Dsite.queue_incoming_increase_max=20000" \
    "-Dsite.commandlog_enable=true" \
    "-Dsite.network_txn_initialization=true" \
    
    # Markov Params
    "-Dsite.markov_enable=$MARKOV_ENABLE" \
    "-Dsite.markov_fixed=$MARKOV_FIXED" \
    "-Dsite.markov_singlep_updates=false" \
    "-Dsite.markov_dtxn_updates=true" \
    "-Dsite.markov_path_caching=true" \
    "-Dsite.specexec_enable=true" \
    "-Dsite.specexec_idle=true" \
    "-Dsite.exec_mispredict_crash=true" \
    
    # Client Params
    "-Dclient.scalefactor=1" \
    "-Dclient.memory=4096" \
    "-Dclient.txnrate=1300" \
    "-Dclient.warmup=0" \
    "-Dclient.duration=60000 "\
    "-Dclient.shared_connection=false" \
    "-Dclient.blocking=false" \
    "-Dclient.blocking_concurrent=1" \
    "-Dclient.throttle_backoff=100" \
    
    # CLIENT DEBUG
    "-Dclient.profiling=false" \
#     "-Dclient.output_markov_profiling=markovprofile.csv" \
#     "-Dclient.output_site_profiling=siteprofile.csv" \
#     "-Dclient.output_txn_counters=txncounters.csv" \
#     "-Dclient.output_txn_counters_combine=true" \
#     "-Dclient.output_basepartitions=true" \
#     "-Dclient.jvm_args=\"-verbose:gc -XX:+PrintGCDetails -XX:+PrintGCTimeStamps -XX:-TraceClassUnloading\"" \
)

FILES_TO_COPY=( \
    "${BASE_PROJECT}.jar" \
    "properties/default.properties" \
    "properties/benchmarks/${BASE_PROJECT}.properties" \
)

for SITE_HOST in ${SITE_HOSTS[@]}; do
    ssh ${SITE_HOST} "cd ${BASE_DIR} && git pull && ant compile" || exit -1
done

UPDATED_HOSTS=$SITE_HOSTS
for CLIENT_HOST in ${CLIENT_HOSTS[@]}; do
    found=0
    for H in ${UPDATED_HOSTS[@]}; do
        if [ "$H" = "$CLIENT_HOST" ]; then
            found=1
            break
        fi
    done
    if [ $found = 0 ]; then
        ssh ${CLIENT_HOST} "cd ${BASE_DIR} && git pull && ant compile" || exit -1
        UPDATED_HOSTS=("${UPDATED_HOSTS[@]}" $CLIENT_HOST)
    fi
done

for i in 4 8 16 24; do
    if [ "$i" = 4 ]; then
        HSTORE_HOSTS="modis:0:0-3"
    fi
    if [ "$i" = 8 ]; then
        HSTORE_HOSTS="modis:0:0-7"
    fi
    if [ "$i" = 16 ]; then
        HSTORE_HOSTS="modis:0:0-7;modis2:1:8-15"
    fi
    if [ "$i" = 24 ]; then
        HSTORE_HOSTS="modis:0:0-7;modis2:1:8-15;vise5:1:16-23"
    fi
    
    NUM_CLIENTS=`expr $i \* $BASE_CLIENT_THREADS`
    NUM_CLIENTS=1
    SITE_MEMORY=`expr $BASE_SITE_MEMORY + \( $i \* $BASE_SITE_MEMORY_PER_PARTITION \)`
    
    # BUILD PROJECT JAR
    ant hstore-prepare \
        -Dproject=${BASE_PROJECT} \
        -Dhosts=${HSTORE_HOSTS} \
        -Dpartitionplan=files/designplans/${BASE_PROJECT}.lns.pplan \
        -Dpartitionplan.ignore_missing=True 
    test -f ${BASE_PROJECT}.jar || exit -1
    
    # BUILD MARKOVS FILE
    MARKOV_FILE="$MARKOV_DIR/${BASE_PROJECT}-${i}p.markov.gz"
    if [ $MARKOV_FIXED != "true" -a $MARKOV_ENABLE = "true" -a ! -f $MARKOV_FILE ]; then
        ant markov-generate -Dproject=$BASE_PROJECT \
            -Dworkload=files/workloads/$BASE_PROJECT.100p-1.trace.gz \
            -Dglobal=false \
            -Dvolt.client.memory=10000 \
            -Doutput=$BASE_PROJECT.markov
        gzip --force --best $BASE_PROJECT.markov
        mv $BASE_PROJECT.markov.gz $MARKOV_FILE
    fi
    
    
    for file in ${FILES_TO_COPY[@]}; do
        for SITE_HOST in ${SITE_HOSTS[@]}; do
            if [ $SITE_HOST != `hostname` ]; then
                scp $file ${SITE_HOST}:${BASE_DIR}/$file || exit -1
            fi
        done
    done
    if [ $MARKOV_FIXED != "true" -a $MARKOV_ENABLE = "true" -a -f $MARKOV_FILE ]; then
        if [ $SITE_HOST != `hostname` ]; then
            scp ${MARKOV_FILE} ${SITE_HOST}:${BASE_DIR}/${MARKOV_FILE}
        fi
    fi

    # UPDATE CLIENTS
    CLIENT_COUNT=0
    CLIENT_HOSTS_STR=""
    for CLIENT_HOST in ${CLIENT_HOSTS[@]}; do
        if [ $CLIENT_HOST != `hostname` ]; then
            for file in ${FILES_TO_COPY[@]}; do
                scp $file ${CLIENT_HOST}:${BASE_DIR}/$file || exit -1
            done
        fi
        CLIENT_COUNT=`expr $CLIENT_COUNT + 1`
        if [ ! -z "$CLIENT_HOSTS_STR" ]; then
            CLIENT_HOSTS_STR="${CLIENT_HOSTS_STR},"
        fi
        CLIENT_HOSTS_STR="${CLIENT_HOSTS_STR}${CLIENT_HOST}"
    done
    
    # EXECUTE BENCHMARK
    ant hstore-benchmark ${BASE_ARGS[@]} \
        -Dproject=${BASE_PROJECT} \
        -Dkillonzero=true \
        -Dmarkov=${MARKOV_FILE} \
        -Dmarkov.recompute_end=${MARKOV_RECOMPUTE} \
        -Dclient.threads_per_host=${NUM_CLIENTS} \
        -Dsite.memory=${SITE_MEMORY} \
        -Dclient.hosts=${CLIENT_HOSTS_STR} \
        -Dclient.count=${CLIENT_COUNT}
    result=$?
    if [ $result != 0 ]; then
        exit $result
    fi
done
