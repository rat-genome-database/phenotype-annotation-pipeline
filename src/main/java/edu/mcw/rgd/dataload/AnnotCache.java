package edu.mcw.rgd.dataload;


import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.process.Utils;

import java.util.*;

/**
 * @author mtutaj
 * @since 2020-03-03
 */
public class AnnotCache {

    private Map<String, Annotation> _cacheMap = new HashMap<>();
    private Set<Integer> annotKeysUpdated = new HashSet<>();
    // in-RGD annotations with a need to update DATA_SRC, NOTES or RELATIVE_TO
    private Map<Integer, Annotation> annotsForUpdate = new HashMap<>();

    public int getCountOfUpToDateAnnots() {
        return annotKeysUpdated.size();
    }

    public int getCountOfModifiedAnnots() {
        return annotsForUpdate.size();
    }

    /**
     * get list of in-rgd annotations that have not been updated by the pipeline
     * @return List of Annotation objects
     */
    public List<Annotation> getStaleAnnotations(Date cutOffDate) {
        long cutOffTime = cutOffDate.getTime();

        List<Annotation> staleAnnots = new ArrayList<>();
        for( Annotation a: _cacheMap.values() ) {

            // do not count newly inserted annotations as stale
            if( a.getLastModifiedDate().getTime() > cutOffTime ) {
                continue;
            }

            if( annotsForUpdate.containsKey(a.getKey()) ) {
                continue;
            }

            if( !annotKeysUpdated.contains(a.getKey()) ) {
                staleAnnots.add(a);
            }
        }
        return staleAnnots;
    }

    /**
     * load pipeline annotations from the database
     * @return count of annotations loaded
     */
    public int loadAnnotations(AnnotationImportDao dao, int refRgdId) throws Exception {

        List<Annotation> annotations = dao.getAnnotations(refRgdId);
        for( Annotation a: annotations ) {
            String annotKey = createAnnotKey(a);
            Annotation aOld = _cacheMap.put(annotKey, a);
            if( aOld!=null ) {
                System.out.println("unexpected: duplicate annot");
                System.out.println("   "+aOld.dump("|"));
                System.out.println("   "+a.dump("|"));
            }
        }
        return annotations.size();
    }

    /**
     *
     * @param a incoming annotation (requires REF_RGD_ID, RGD_ID, TERM_ACC, XREF_SOURCE, QUALIFIER, WITH_INFO, EVIDENCE to be set
     * @return Annotation object in RGD, or null
     */
    public Annotation getAnnotInRgd(Annotation a) {
        String annotKey = createAnnotKey(a);
        return _cacheMap.get(annotKey);
    }

    public void insert(Annotation a) throws Exception {
        String annotKey = createAnnotKey(a);
        Annotation oldAnnot = _cacheMap.put(annotKey, a);
        if( oldAnnot!=null ) {
            throw new Exception("unexpected: duplicate annot: "+a.dump("|"));
        }
    }

    public int insertOrUpdateAnnotation(Annotation annot, AnnotationImportDao dao) throws Exception {

        Annotation annotInRgd = getAnnotInRgd(annot);

        // inserted
        if( annotInRgd==null ) {
            int fullAnnotKey = dao.insertAnnotation(annot);
            insert(annot);
            return fullAnnotKey;
        }

        // updated or up-to-date
        annot.setKey(annotInRgd.getKey());
        annotKeysUpdated.add(annotInRgd.getKey());

        // check if fields changed: DATA_SRC, NOTES
        if( !areSameAnnotations(annotInRgd, annot) ) {

            Annotation a = annotsForUpdate.get(annot.getKey());
            if( a==null ) {
                Annotation aForUpdate = (Annotation) annot.clone();
                annotsForUpdate.put(annot.getKey(), aForUpdate);
            } else {
                // we already have an annotation for update:
                a.setNotes(merge(a.getNotes(), annot.getNotes()));
            }
        }
        return 0;
    }

    String merge(String s1, String s2) {
        if( s1==null ) {
            return s2;
        }
        if( s2==null ) {
            return s1;
        }

        Set<String> words = new TreeSet<>();
        words.addAll(Arrays.asList(s1.split("[\\s]+")));
        words.addAll(Arrays.asList(s2.split("[\\s]+")));
        return Utils.concatenate(words, " ");
    }

    public int updateAnnotations(AnnotationImportDao dao) throws Exception {

        for( Annotation a: annotsForUpdate.values() ) {
            dao.updateAnnotation(a, getAnnotInRgd(a));
        }
        return annotsForUpdate.size();
    }
    /**
     * compare fields that are NOT in FULL_ANNOT UNIQUE KEY: DATA_SRC, NOTES
     * @param a1 Annotation object 1
     * @param a2 Annotation object 2
     * @return true or false
     */
    public boolean areSameAnnotations(Annotation a1, Annotation a2) {

        return Utils.stringsAreEqual(a1.getDataSrc(), a2.getDataSrc())
            && Utils.stringsAreEqual(a1.getNotes(), a2.getNotes());
    }

    private String createAnnotKey(Annotation a) {
        return a.getRefRgdId()
            +"|"+a.getAnnotatedObjectRgdId()
            +"|"+a.getTermAcc()
            +"|" + Utils.defaultString(a.getXrefSource())
            +"|" + Utils.defaultString(a.getQualifier())
            +"|" + Utils.defaultString(a.getWithInfo())
            +"|" + Utils.defaultString(a.getEvidence())
            +"|" + Utils.defaultString(a.getQualifier2())
            +"|" + Utils.defaultString(a.getAssociatedWith());
    }
}
