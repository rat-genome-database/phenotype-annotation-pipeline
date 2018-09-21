#!/usr/bin/env bash
#
# phenotype annotation loading pipeline
#
. /etc/profile
APPNAME=annotationImport

APPDIR=/home/rgddata/pipelines/$APPNAME
cd $APPDIR
pwd
DB_OPTS="-Dspring.config=$APPDIR/../properties/default_db.xml"
LOG4J_OPTS="-Dlog4j.configuration=file://$APPDIR/properties/log4j.properties"
declare -x "ANNOTATION_IMPORT_OPTS=$DB_OPTS $LOG4J_OPTS"
bin/$APPNAME "$@"