# load MP (Mouse Phenotype Ontology) annotations
#
APPHOME=/home/rgddata/pipelines/PhenotypeAnnotation
SERVER=`hostname -s | tr '[a-z]' '[A-Z]'`

EMAIL_LIST=mtutaj@mcw.edu
if [ "$SERVER" = "REED" ]; then
  EMAIL_LIST=rgd.developers@mcw.edu
fi

$APPHOME/run.sh -MGIPhenotype 2>&1 > run.log

mailx -s "[$SERVER] Mouse Phenotype Annotation Import" $EMAIL_LIST < $APPHOME/logs/status_mouse.log
