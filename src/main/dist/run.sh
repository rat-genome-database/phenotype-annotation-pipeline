#!/usr/bin/env bash
#
# phenotype annotation loading pipeline master script
#
. /etc/profile
APPNAME=phenotype-annotation-pipeline

APPDIR=/home/rgddata/pipelines/$APPNAME
cd $APPDIR

java -Dspring.config=$APPDIR/../properties/default_db2.xml \
    -Dlog4j.configurationFile=file://$APPDIR/properties/log4j2.xml \
    -jar lib/${APPNAME}.jar "$@"