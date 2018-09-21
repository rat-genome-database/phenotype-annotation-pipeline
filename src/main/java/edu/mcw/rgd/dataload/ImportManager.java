package edu.mcw.rgd.dataload;

import edu.mcw.rgd.process.Utils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.FileSystemResource;

/**
 * Created by IntelliJ IDEA.
 * User: mtutaj
 * Date: Oct 11, 2010
 * Time: 1:56:16 PM
 */
public class ImportManager {

    Logger log = Logger.getLogger("core");
    private String version;

    /**
     * Annotation importer: specify in cmdline what to run: MGIPhenotype importer or HPOPhenotype importer
     *
     * @param args cmdline arguments
     * @throws Exception
     */
    static public void main(String[] args) throws Exception {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        new XmlBeanDefinitionReader(bf).loadBeanDefinitions(new FileSystemResource("properties/AppConfigure.xml"));
        ImportManager manager = (ImportManager) (bf.getBean("manager"));
        manager.log.info(manager.getVersion());

        if( args.length==0 ) {
            usage();
        }
        String beanId = "";

        if( args[0].equals("-MGIPhenotype") ) {
            beanId = "mgiPhenotype";
        }else if( args[0].equals("-HPOPhenotype") ) {
            beanId = "hpoPhenotype";
        }else {
            usage();
        }

        long startTime = System.currentTimeMillis();

        BaseImporter importer = (BaseImporter) bf.getBean(beanId);
        try {
            importer.run();
        }catch (Exception e) {
            importer.log.error(e);
            throw e;
        }

        importer.log.info("OK -- elapsed "+ Utils.formatElapsedTime(startTime, System.currentTimeMillis()));
    }

    private static void usage() {
        System.out.println("Usage: java -Dspring.config=[path] -Dlog4j.configuration=file:///[path] -jar annotationImport.jar "+
                "-MGIPhenotype|-HPOPhenotype");
        System.exit(1);
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getVersion() {
        return version;
    }
}

