package edu.mcw.rgd.dataload;

import edu.mcw.rgd.dao.impl.AnnotationDAO;
import edu.mcw.rgd.dao.impl.OntologyXDAO;
import edu.mcw.rgd.dao.impl.RGDManagementDAO;
import edu.mcw.rgd.dao.impl.XdbIdDAO;
import edu.mcw.rgd.dao.spring.StringListQuery;
import edu.mcw.rgd.datamodel.RgdId;
import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import org.apache.log4j.Logger;

import java.util.*;

/**
 * @author mtutaj
 * @since 6/19/12
 * Centralized management of all dao code
 */
public class AnnotationImportDao {

    XdbIdDAO xdao = new XdbIdDAO();
    AnnotationDAO adao = new AnnotationDAO();
    OntologyXDAO odao = new OntologyXDAO();
    RGDManagementDAO mdao = new RGDManagementDAO();

    List<RgdId> getRGDIdsByXdbId(int xdbKey, String accId) throws Exception {
        String key = xdbKey+"|"+accId;
        List<RgdId> list = _cache.get(key);
        if( list==null ) {
            list = xdao.getRGDIdsByXdbId(xdbKey, accId);
            _cache.put(key, list);
        }
        return list;
    }
    private Map<String,List<RgdId>> _cache = new HashMap<>();


    List<Annotation> getAnnotationByEvidence(int rgdId, String accId, int ownerId, String evidence) throws Exception {
        return adao.getAnnotationByEvidence(rgdId, accId, ownerId, evidence);
    }

    public List<Annotation> getAnnotations(int rgdId, String termAcc) throws Exception {
        return adao.getAnnotations(rgdId, termAcc);
    }

    public int getCountOfAnnotationsByReference(int refRgdId, String src) throws Exception {
        return adao.getCountOfAnnotationsByReference(refRgdId, src);
    }

    /**
     * get all annotations for given owner modified before given point of time;
     * log them into a file 'deletedAnnots.log and then delete them;
     * HOWEVER, if the number of to-be-deleted stale annotations is more than the specified threshold,
     * no annotations will be deleted
     *
     * @param ownerId owner id (unique id for the pipeline)
     * @param dt Date object
     * @return count of deleted annotations
     * @throws Exception
     */
    public int deleteAnnotations(int ownerId, Date dt, int staleAnnotThreshold) throws Exception {

        // get to-be-deleted stale annots and check if their nr does not exceed the threashold
        Logger log = Logger.getLogger("deleted_annots");
        List<Annotation> staleAnnots = adao.getAnnotationsModifiedBeforeTimestamp(ownerId, dt);
        if( staleAnnots.size() > staleAnnotThreshold ) {
            for( Annotation annot: staleAnnots ) {
                log.debug("TO-BE-DELETED "+annot.dump("|"));
            }
            return staleAnnots.size();
        }

        // dump all to be deleted annotation to 'deleted_annots' log
        for( Annotation annot: staleAnnots ) {
            log.info("DELETED "+annot.dump("|"));
        }

        // delete the annotations
        return adao.deleteAnnotations(ownerId, dt);
    }

    int getAnnotationKey(Annotation annot) throws Exception {
        return adao.getAnnotationKey(annot);
    }

    public String getAnnotationNotes(int annotKey) throws Exception {

        String sql = "SELECT notes FROM full_annot a WHERE a.full_annot_key=?";
        List<String> results = StringListQuery.execute(adao, sql, annotKey);
        return results.isEmpty() ? null : results.get(0);
    }

    public int updateAnnotationNotes(int fullAnnotKey, String notes) throws Exception {
        String sql = "UPDATE full_annot SET notes=? WHERE full_annot_key=?";
        return adao.update(sql, notes, fullAnnotKey);
    }

    private List<Integer> annotKeysForUpdate = new ArrayList<>();

    public void updateLastModified(int annotKey) throws Exception {
        annotKeysForUpdate.add(annotKey);
        if( annotKeysForUpdate.size()>=500 ) {
            finishUpdateOfLastModified();
        }
    }

    public void finishUpdateOfLastModified() throws Exception {
        adao.updateLastModified(annotKeysForUpdate);
        annotKeysForUpdate.clear();
    }

    int insertAnnotation(Annotation annot) throws Exception {
        // dump to be inserted annotation to 'inserted_annots' log
        Logger log = Logger.getLogger("inserted_annots");
        // insert the annotation
        int r = adao.insertAnnotation(annot);
        log.info("INSERTED "+annot.dump("|"));
        return r;
    }

    Object getObject(int rgdId) throws Exception {

        Object obj = _objectCache.get(rgdId);
        if( obj==null ) {
            // object not in cache yet
            obj = mdao.getObject(rgdId);
            _objectCache.put(rgdId, obj);
        }
        return obj;
    }
    // private object cache to greatly reduce number of roundtrips to database server
    private Map<Integer, Object> _objectCache = new HashMap<>(1003);


    public Term getTermByAccId(String accId) throws Exception {
        return odao.getTermWithStatsCached(accId);
    }


    public List<Term> getRdoTermsBySynonym(String id) throws Exception {
        List<Term> terms = _omimCache.get(id);
        if( terms==null ) {
            terms = odao.getTermsBySynonym("RDO", id, "exact");
            _omimCache.put(id, terms);
        }
        return terms;
    }
    private Map<String,List<Term>> _omimCache = new HashMap<>();

}
