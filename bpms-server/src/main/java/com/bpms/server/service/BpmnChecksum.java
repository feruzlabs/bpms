package com.bpms.server.service;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Canonical BPMN XML → SHA-256 hex (plan 35). Whitespace-only text nodes are stripped and the document is
 * re-serialized so pretty-printed vs compact identical models share one checksum.
 */
public final class BpmnChecksum {

    private BpmnChecksum() {
    }

    public static String sha256Canonical(byte[] xmlBytes) {
        try {
            String canonical = canonicalize(xmlBytes);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot checksum BPMN XML", e);
        }
    }

    static String canonicalize(byte[] xmlBytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setIgnoringElementContentWhitespace(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xmlBytes));
        doc.normalizeDocument();
        stripIgnorableWhitespace(doc.getDocumentElement());

        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.toString().replace("\r\n", "\n").replace("\r", "\n").trim();
    }

    private static void stripIgnorableWhitespace(Node node) {
        if (node == null) {
            return;
        }
        NodeList children = node.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            Node child = children.item(i);
            short type = child.getNodeType();
            if (type == Node.TEXT_NODE || type == Node.CDATA_SECTION_NODE) {
                String text = child.getTextContent();
                if (text == null || text.isBlank()) {
                    node.removeChild(child);
                }
            } else if (type == Node.COMMENT_NODE) {
                node.removeChild(child);
            } else if (type == Node.ELEMENT_NODE) {
                stripIgnorableWhitespace(child);
            }
        }
    }
}
