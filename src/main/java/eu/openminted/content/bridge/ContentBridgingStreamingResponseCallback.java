package eu.openminted.content.bridge;

import eu.openminted.content.mocks.MockIndex;
import eu.openminted.content.mocks.MockIndexImpl;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.StreamingResponseCallback;
import org.apache.solr.client.solrj.impl.BinaryRequestWriter;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.springframework.core.io.Resource;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.bind.JAXBException;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPathExpressionException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class ContentBridgingStreamingResponseCallback extends StreamingResponseCallback {
    private static Logger log = Logger.getLogger(ContentBridgingStreamingResponseCallback.class.getName());
    private String outputField;
    private ContentBridgingHandler handler;
    private SAXParser saxParser;
    private DocumentBuilderFactory domFactory = DocumentBuilderFactory.newInstance();
    private DocumentBuilder builder;
    private Transformer transformer;
    private HttpSolrClient solrClient;
    private Resource resource;

    ContentBridgingStreamingResponseCallback(String field, String host, String defaultCollection, Resource resource) throws JAXBException, ParserConfigurationException, SAXException, TransformerConfigurationException {
        this.outputField = field;
        this.handler = new ContentBridgingHandler();
        SAXParserFactory factory = SAXParserFactory.newInstance();
        this.saxParser = factory.newSAXParser();
        this.domFactory.setIgnoringComments(true);
        this.builder = domFactory.newDocumentBuilder();
        this.transformer = TransformerFactory.newInstance().newTransformer();
        this.solrClient = new HttpSolrClient.Builder(host + "/" + defaultCollection).build();
        this.solrClient.setRequestWriter(new BinaryRequestWriter());

        this.resource = resource;
    }

    @Override
    public void streamSolrDocument(SolrDocument solrDocument) {
        try {
            String xml = solrDocument.getFieldValue(outputField).toString()
                    .replaceAll("\\[|\\]", "");
            xml = xml.trim();

            saxParser.parse(new InputSource(new StringReader(xml)), handler);

            MockIndex index = MockIndexImpl.getIndexInstance();

            boolean hasAbstract = false;
            if (handler.getDescription() != null
                    && !handler.getDescription().isEmpty()) {
                hasAbstract = true;
            }

            IndexResponse indexResponse = null;
            Document doc = builder.parse(new InputSource(new StringReader(xml)));
            Properties properties = new Properties();
            properties.setProperty("identifier", handler.getIdentifier());
            properties.setProperty("fulltext", handler.getFulltext());
            properties.setProperty("mimetype", handler.getFormat());

            if ((indexResponse = index.getOrTryAddHashId(properties)) != null) {

                Node node = doc.getElementsByTagName("oaf:result").item(0);
                Element indexInfoElement = doc.createElement("indexinfo");

                Attr openaireAttrNode = doc.createAttribute("id");
                openaireAttrNode.setValue(indexResponse.getOpenaAireId());

                Text hashKeyText = doc.createTextNode(indexResponse.getHashKey());
                Text mimeTypeText = doc.createTextNode(indexResponse.getMimeType());
                Text urlText = doc.createTextNode(indexResponse.getUrl());

                Element hashKeyElement = doc.createElement("hashkey");
                Element mimeTypeElement = doc.createElement("mimetype");
                Element urlElement = doc.createElement("url");

                hashKeyElement.appendChild(hashKeyText);
                mimeTypeElement.appendChild(mimeTypeText);
                urlElement.appendChild(urlText);

                indexInfoElement.setAttributeNode(openaireAttrNode);
                indexInfoElement.appendChild(hashKeyElement);
                indexInfoElement.appendChild(mimeTypeElement);
                indexInfoElement.appendChild(urlElement);

                node.appendChild(indexInfoElement);
            }

            System.out.println("\nPDF for " + handler.getIdentifier() + " exists: " + index.containsId(handler.getIdentifier()) + "\n");

            if (hasAbstract || index.containsId(handler.getIdentifier())) {
                // Create the new xml with the additional elements
                transformer.setOutputProperty(OutputKeys.INDENT, "yes");
                StreamResult result = new StreamResult(new StringWriter());
                DOMSource source = new DOMSource(doc);
                transformer.transform(source, result);

                String xmlOutput = result.getWriter().toString();

                SolrInputDocument solrInputDocument = new SolrInputDocument();
                Map<String, List<String>> indexedfields = null;

                try {
                    InputStream is = resource.getInputStream();
                    indexedfields = ContentIndexing.indexFields(is, xmlOutput);

                    System.out.println(indexedfields);
                } catch (ParserConfigurationException e) {
                    log.error(this.getClass().toString() + ": Parser Configuration exception", e);
                } catch (XPathExpressionException e) {
                    log.error(this.getClass().toString() + ": XPath Expression exception", e);
                }

                for (Map.Entry<String, Object> f : solrDocument.entrySet()) {
                    // field "version" will be recreated in the new index
                    if (f.getKey().equals("_version_")) continue;
                    solrInputDocument.setField(f.getKey(), f.getValue());
                }

                if (indexedfields != null)
                    for (Map.Entry<String, List<String>> p : indexedfields.entrySet()) {
                        solrInputDocument.addField(p.getKey(), p.getValue());
                    }
                solrInputDocument.setField(outputField, xmlOutput);

                solrClient.add(solrInputDocument);
                solrClient.commit();
            }
        } catch (SAXException | IOException | TransformerException e) {
            log.error("ContentBridgingStreamingResponseCallback.streamSolrDocument", e);
        } catch (SolrServerException e) {
            log.error("ContentBridgingStreamingResponseCallback.SolrServerException", e);
        }
    }

    @Override
    public void streamDocListInfo(long l, long l1, Float aFloat) {

    }
}
