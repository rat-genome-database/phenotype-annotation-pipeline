package edu.mcw.rgd.dataload;

import edu.mcw.rgd.datamodel.ontology.Annotation;
import org.apache.log4j.Logger;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * @author jdepons
 * @since Nov 29, 2010
 * Base class with functionality common for all importers
 */
public abstract class BaseImporter {

    // status log is so configured in log4j.properties file,
    // that info messages and above go into status.log,
    // while debug messages and above go into core.log
    Logger log = Logger.getLogger(getLoggerName());

    AnnotationImportDao dao = new AnnotationImportDao();

    private String version;
    private String dataSource;
    private int refRgdId;
    private int owner;
    private String fileURL;
    private String workDirectory;
    private int staleAnnotThreshold;
    private Date dtStart;

    abstract public String getLoggerName();

    /**
     * return url of file to import
     * @return
     */
    public String getFileURL() {
        return fileURL;
    }

    /**
     * Sets the url of annotation file to import
     * @param fileURL
     */
    public void setFileURL(String fileURL) {
        this.fileURL = fileURL;
    }

    /**
     * Return the working directory
     * @return
     */
    public String getWorkDirectory() {
        return workDirectory;
    }

    /**
     * Set the working directory
     * @param workDirectory
     */
    public void setWorkDirectory(String workDirectory) {
        this.workDirectory = workDirectory;
    }

    public void run() throws Exception {
        dtStart = new Date();
        log.info(getVersion());
        log.info("   "+dao.getConnectionInfo());
        SimpleDateFormat sdt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        log.info("   started at "+sdt.format(dtStart));
    }


    /**
     * insert or update an annotation
     * @param annot Annotation object
     * @return full annot key of the just inserted annotation, or 0 if annotation's last modified time has been updated
     * @throws Exception
     */
    int insertOrUpdateAnnotation(Annotation annot) throws Exception {

        // check for duplicate annotations
        int annotKey = dao.getAnnotationKey(annot);

        if( annotKey!=0 ) {
            // at this point all fields are equal, so we have a duplicate
            annot.setKey(annotKey);
            dao.updateLastModified(annotKey);
            //log.debug("updated RGDID:" + annot.getAnnotatedObjectRgdId() + " " + annot.getTermAcc() + " " + annot.getXrefSource());
            return 0;
        }

        return dao.insertAnnotation(annot);
    }

    public void deleteStaleAnnotations() throws Exception {

        // get total number of annotations in database
        int totalAnnots = dao.getCountOfAnnotationsByReference(getRefRgdId(), getDataSource());

        // compute maximum allowed number of stale annotations to be deleted
        int staleAnnotDeleteLimit = (getStaleAnnotThreshold() * totalAnnots) / 100;

        final int recordsRemoved = dao.deleteAnnotations(getOwner(), dtStart, staleAnnotDeleteLimit);
        if( recordsRemoved > staleAnnotDeleteLimit ) {
            log.info("*** stale annotations "+getStaleAnnotThreshold()+"% threshold is "+staleAnnotDeleteLimit);
            log.warn("*** DELETE ABORTED: count of stale annotations "+recordsRemoved+" exceeds the allowed limit of "+staleAnnotDeleteLimit);
        } else if( recordsRemoved!=0 ){
            log.info(recordsRemoved + " stale annotations have been removed");
        }
    }


    public void setVersion(String version) {
        this.version = version;
    }

    public String getVersion() {
        return version;
    }

    public String getDataSource() {
        return dataSource;
    }

    public void setDataSource(String dataSource) {
        this.dataSource = dataSource;
    }

    public int getRefRgdId() {
        return refRgdId;
    }

    public void setRefRgdId(int refRgdId) {
        this.refRgdId = refRgdId;
    }

    public int getOwner() {
        return owner;
    }

    public void setOwner(int owner) {
        this.owner = owner;
    }

    public void setStaleAnnotThreshold(int staleAnnotThreshold) {
        this.staleAnnotThreshold = staleAnnotThreshold;
    }

    public int getStaleAnnotThreshold() {
        return staleAnnotThreshold;
    }
}
