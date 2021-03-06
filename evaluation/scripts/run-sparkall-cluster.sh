#!/bin/bash

##
# Script to run  a Query on Sparkall
#

# Cluster Nodes_list
declare -a Nodes_list=("host1" "host2" "host3")

#Settings
export SPARK=/data/home/MohamedMami/spark-2.1.0-bin-hadoop2.7/bin
export SPARKALL_HOME=/data/home/MohamedMami/evaluations/sparkall

# Input Parameters
SPARK_MASTER=$1; # Spark master
EXECUTOR_MEMORY=$2; # Ammount of memory allocated to Spark
QUERIES_LOCATION=$3; #Path where the benchmark of queries is located
MAPPINGS_FILE=$4
CONFIG_FILE=$5
REORDER_FLAG=$6 # Flag reordering
RESULT_FILE=$7; #Filename where the query output will be stored

for i in `ls $QUERIES_LOCATION/*.sparql`; do

  # sudo echo "------- FLUSHING CACHE -------" >> $RESULT_FILE;
  for n in "${Nodes_list[@]}"
  do
      # echo "Clearing cache of: " $n
      echo "Clearing cache of: " $n | sudo tee --append $RESULT_FILE > /dev/null
      ssh hduser@$n "echo sync && echo 3 | sudo tee /proc/sys/vm/drop_caches"
  done;
  wait

  echo $i | sudo tee --append $RESULT_FILE > /dev/null
  # echo $i >> $RESULT_FILE;

  # Run
  /usr/bin/time  -f "%e" $SPARK/spark-submit --class org.sparkall.Main --executor-memory $EXECUTOR_MEMORY --master $SPARK_MASTER $SPARKALL_HOME/sparkall.jar $i $MAPPINGS_FILE $CONFIG_FILE $SPARK_MASTER $REORDER_FLAG |&  sudo tee --append $RESULT_FILE > /dev/null

  # ../spark-2.1.0-bin-hadoop2.7/bin/spark-submit --class org.sparkall.Main --executor-memory 200G --master spark://host:port sparkall.jar query3.sparql mappings.ttl config spark://host:port r
done
