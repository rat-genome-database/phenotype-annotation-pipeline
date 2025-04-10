# load HP (Human Phenotype Ontology) annotations
#
APPHOME="/home/rgddata/pipelines/phenotype-annotation-pipeline"
SERVER=`hostname -s | tr '[a-z]' '[A-Z]'`

EMAIL_LIST=mtutaj@mcw.edu
if [ "$SERVER" = "REED" ]; then
  EMAIL_LIST=rgd.devops@mcw.edu
fi

$APPHOME/run.sh -HPOPhenotype 2>&1 > $APPHOME/run_human.log

mailx -s "[$SERVER] Human Phenotype Annotation Import" $EMAIL_LIST < $APPHOME/logs/summary_human.log
