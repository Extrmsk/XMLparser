package xmlparser;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Scanner;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.multipart.FilePart;
import org.apache.commons.httpclient.methods.multipart.MultipartRequestEntity;
import org.apache.commons.httpclient.methods.multipart.Part;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 *
 * @author Lemanov Egor
 */
public class XMLparser {
    private static DocumentBuilder docBuilder;
    private static Document srcDoc, dstDoc;
    private static String srcFolder;
    private static String dstFolder;
    private static String[] srcFileNames, dstFileNames;
    private static boolean isDstFolderExists;
    private static String URL = "http://localhost:8080/";

    /**
     * @param args the command line arguments
     * @throws javax.xml.parsers.ParserConfigurationException
     * @throws org.xml.sax.SAXException
     * @throws java.io.IOException
     */
    public static void main(String[] args) throws ParserConfigurationException, SAXException, TransformerException, IOException {
        System.out.println("Enter the path to XML folder: ");
        Scanner sc = new Scanner(System.in);
        srcFolder = sc.nextLine();
        dstFolder = srcFolder + "/" + "ParsedXML";
        
        //scan and find src XML files
        srcFileNames = getXMLfileList(srcFolder);
        if (srcFileNames.length == 0) {
            System.out.println("No XML files found. Program exit");
            System.exit(1);
        } else {
            printSrcFiles();
        }
        
        parseFilesAndSave();
        
        //scan dst folder and get filenames
        if (isDstFolderExists) {
            dstFileNames = getXMLfileList(dstFolder);
            printDstFiles();
        } else {
            System.out.println("No parsed files. Program exit");
            System.exit(1);
        }
        
        sendXMLfileViaHTTP(dstFileNames);
    }
    
    private static String[] getXMLfileList(String path) {
        File dir = new File(path);
        if (!dir.exists()) {
            System.out.println("Wrong path. File not found. Program exit");
            System.exit(1);
        }
        FilenameFilter ff = new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.toLowerCase().endsWith(".xml");
            }
        };
        return dir.list(ff);
    }
    
    private static void parseFilesAndSave() throws ParserConfigurationException, SAXException, TransformerException, IOException {
        docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        for (int k = 0; k < srcFileNames.length; k++) {
            //init src XML
            srcDoc = docBuilder.parse(srcFolder + "/" + srcFileNames[k]);

            //init new XML
            dstDoc = docBuilder.newDocument();
            Element data = dstDoc.createElement("Data");
            dstDoc.appendChild(data);

            //get field nodes
            NodeList fields = srcDoc.getElementsByTagName("Field");
            if (fields.item(0) == null) { //if wrong format XML, switch to next file
                System.out.println(srcFileNames[k] + " file format is not supported. Skipped");
            } else {
                for (int j = 0; j < fields.getLength(); j++) {
                    setFieldToNewXML(fields.item(j), dstDoc);
                }
                saveDstXml(k);
            }
        }
    }
    private static void setFieldToNewXML(Node fieldNode, Document dstDoc) {
        Element root = (Element) dstDoc.getFirstChild();

        //create new node in dstXML
        Element field = (Element) fieldNode;
        String typeContent = field.getElementsByTagName("type").item(0).getTextContent();
        Element param = dstDoc.createElement(typeContent);

        NodeList parameters = fieldNode.getChildNodes();
        for (int i = 0; i < parameters.getLength(); i++) {
            String name = parameters.item(i).getNodeName();
            String content = parameters.item(i).getTextContent();

            if (parameters.item(i).getNodeType() != parameters.item(i).TEXT_NODE) { //exclude unnecessary nodes
                if (name != "type") {// "type" has already been used

                    //cast 1/0 to true/false
                    if (name == "required" || name == "digitOnly" || name == "readOnly") {
                        int contentInt = Integer.parseInt(content);
                        if (contentInt == 1) {
                            content = "true";
                        } else {
                            content = "false";
                        }
                    }

                    //Address parser
                    if ("Address".equals(typeContent) && "value".equals(name)) {
                        String str = content;
                        int endIndex = str.indexOf(",");
                        String streetValue = str.substring(0, endIndex);
                        str = str.substring(endIndex + 1);
                        endIndex = str.indexOf(",");
                        String houseValue = str.substring(0, endIndex);
                        str = str.substring(endIndex + 1);
                        String flatValue = str;

                        param.setAttribute("street", streetValue);
                        param.setAttribute("house", houseValue);
                        name = "flat";
                        content = flatValue;
                    }

                    param.setAttribute(name, content);
                }
            }
        }
        root.appendChild(param);
    }
    private static void saveDstXml(int ID) throws TransformerConfigurationException, TransformerException {
        dstDoc.setXmlStandalone(true); //for removing standalone attribute in output

        File dir = new File(dstFolder);
        if (!dir.exists()) {
            System.out.println("Destination folder not found. Create folder.");
            System.out.println("Destination folder path is = " + dstFolder);
            dir.mkdir();
        }

        isDstFolderExists = true;
        File dstFile = new File(dstFolder + "/dst" + ID + ".xml");
        Transformer tr = TransformerFactory.newInstance().newTransformer();
        tr.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no"); //remove standalone attribute in output
        tr.setOutputProperty(OutputKeys.INDENT, "yes"); //word wrap on 
        DOMSource source = new DOMSource(dstDoc);
        StreamResult str = new StreamResult(dstFile);
        tr.transform(source, str);
    }
    
    private static void sendXMLfileViaHTTP(String[] dstFiles) { //using the media-type multipart/form-data
        PostMethod post = new PostMethod(URL);

        try {
            //load files to entity array
            Part[] entityParts = new Part[dstFiles.length];
            for (int i = 0; i < dstFiles.length; i++) {
                String filePath = dstFolder + "/" + dstFiles[i];
                File file = new File(filePath);
                entityParts[i] = new FilePart(dstFiles[i], file);
            }

            post.setRequestHeader("Content-type", "text/xml");
            post.setRequestEntity(new MultipartRequestEntity(entityParts, post.getParams()));

            HttpClient httpclient = new HttpClient();
            System.out.println("Sent files to web");
            int result = httpclient.executeMethod(post);

            System.out.println("Status: " + post.isRequestSent());
            System.out.println("URL = " + post.getURI());
            System.out.println("Response status code: " + result);
            System.out.println("Response body: ");
            System.out.println(post.getResponseBodyAsString());

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            post.releaseConnection();
        }
    }
    
    private static void printSrcFiles() {
        System.out.println("Finded XML files: ");
        for (int i = 0; i < srcFileNames.length; i++) {
            System.out.println(srcFileNames[i]);
        }
    }
    private static void printDstFiles() {
        System.out.println("Parsed XML files: ");
        for (int i = 0; i < dstFileNames.length; i++) {
            System.out.println(dstFileNames[i]);
        }
    }
}

