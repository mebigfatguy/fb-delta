package com.mebigfatguy.fbdelta;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class FBDeltaTask extends Task {

    private File baseReport;
    private File updateReport;
    private File outputReport;
    private String changedPropertyName;

    public void setBaseReport(File base) {
        baseReport = base;
    }

    public void setUpdateReport(File update) {
        updateReport = update;
    }

    public void setOutputReport(File output) {
        outputReport = output;
    }

    public void setChanged(String changedProperty) {
        changedPropertyName = changedProperty;
    }

    @Override
    public void execute() {
        try {
            if ((baseReport == null) || (!baseReport.isFile())) {
                throw new BuildException("'baseReport' is not specified or is invalid");
            }
            if ((updateReport == null) || (!updateReport.isFile())) {
                throw new BuildException("'updateReport' is not specified or is invalid");
            }

            Map<String, Map<String, Set<String>>> baseData = parseReport(baseReport);
            Map<String, Map<String, Set<String>>> updateData = parseReport(updateReport);

            removeDuplicates(baseData, updateData);

            if (outputReport != null) {
                outputReport.getParentFile().mkdirs();
                try (PrintWriter pw = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outputReport), StandardCharsets.UTF_8)))) {
                    pw.println("Changes found in Base Report but not in Update Report (FIXED)");
                    pw.println();
                    for (Map.Entry<String, Map<String, Set<String>>> clsEntry : baseData.entrySet()) {
                        pw.println("Class: " + clsEntry.getKey());
                        for (Map.Entry<String, Set<String>> typeEntry : clsEntry.getValue().entrySet()) {
                            pw.println("\tType: " + typeEntry.getKey() + "\tCount: " + typeEntry.getValue().size());
                        }
                        pw.println();
                    }

                    pw.println();
                    pw.println("Changes found in Update Report but not in Base Report (NEW)");
                    pw.println();
                    for (Map.Entry<String, Map<String, Set<String>>> clsEntry : updateData.entrySet()) {
                        pw.println("Class: " + clsEntry.getKey());
                        for (Map.Entry<String, Set<String>> typeEntry : clsEntry.getValue().entrySet()) {
                            pw.println("\tType: " + typeEntry.getKey() + "\tCount: " + typeEntry.getValue().size());
                        }
                        pw.println();
                    }
                }
            }

            if (changedPropertyName != null) {
                getProject().setProperty(changedPropertyName, String.valueOf(!baseData.isEmpty() || !updateData.isEmpty()));
            }

        } catch (Exception e) {
            throw new BuildException("Failed to run fb-delta on report of " + baseReport + " to " + updateReport, e);
        }
    }

    private Map<String, Map<String, Set<String>>> parseReport(File reportFile)
            throws IOException, SAXException, ParserConfigurationException, XPathExpressionException {

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document d = db.parse(reportFile);

        XPathFactory xpf = XPathFactory.newInstance();
        XPath xp = xpf.newXPath();

        XPathExpression bugInstanceXPE = xp.compile("/BugCollection/BugInstance");
        XPathExpression classNameXPE = xp.compile("./Class/@classname");
        XPathExpression bugXPE = xp.compile("./Method|./Field");
        XPathExpression sourceLineXPE = xp.compile("./SourceLine");

        Map<String, Map<String, Set<String>>> report = new HashMap<>();

        NodeList bugsNodes = (NodeList) bugInstanceXPE.evaluate(d, XPathConstants.NODESET);
        for (int i = 0; i < bugsNodes.getLength(); i++) {
            Element bugElement = (Element) bugsNodes.item(i);
            String type = bugElement.getAttribute("type");
            String className = (String) classNameXPE.evaluate(bugElement, XPathConstants.STRING);

            Element bugDetailElement = (Element) bugXPE.evaluate(bugElement, XPathConstants.NODE);
            String bugData = null;
            if (bugDetailElement != null) {
                bugData = bugDetailElement.getAttribute("name") + bugDetailElement.getAttribute("signature");
            } else {
                Element sourceLineElement = (Element) sourceLineXPE.evaluate(bugElement, XPathConstants.NODE);
                if (sourceLineElement != null) {
                    bugData = sourceLineElement.getAttribute("start") + "-" + sourceLineElement.getAttribute("end");
                } else {
                    bugData = "UNKNOWN " + Math.random();
                }
            }

            Map<String, Set<String>> classBugs = report.get(className);
            if (classBugs == null) {
                classBugs = new HashMap<>();
                report.put(className, classBugs);
            }

            Set<String> bugs = classBugs.get(type);
            if (bugs == null) {
                bugs = new HashSet<>();
                classBugs.put(type, bugs);
            }

            bugs.add(bugData);
        }

        return report;
    }

    private void removeDuplicates(Map<String, Map<String, Set<String>>> baseData, Map<String, Map<String, Set<String>>> updateData) {
        Iterator<Map.Entry<String, Map<String, Set<String>>>> clsIt = baseData.entrySet().iterator();
        while (clsIt.hasNext()) {
            Map.Entry<String, Map<String, Set<String>>> clsEntry = clsIt.next();
            String clsName = clsEntry.getKey();
            Map<String, Set<String>> baseTypeMap = clsEntry.getValue();
            Map<String, Set<String>> updateTypeMap = updateData.get(clsName);
            if (updateTypeMap == null) {
                continue;
            }

            Iterator<Map.Entry<String, Set<String>>> typeIt = baseTypeMap.entrySet().iterator();
            while (typeIt.hasNext()) {
                Map.Entry<String, Set<String>> typeEntry = typeIt.next();
                String baseType = typeEntry.getKey();
                Set<String> baseBugs = typeEntry.getValue();

                Set<String> updateBugs = updateTypeMap.get(baseType);
                if (updateBugs == null) {
                    continue;
                }

                if (baseBugs.size() == updateBugs.size()) {
                    // this ignores the add 1/remove 1 issue but probably not a problem
                    typeIt.remove();
                    updateTypeMap.remove(baseType);
                    continue;
                }

                Iterator<String> bugIt = updateBugs.iterator();
                while (bugIt.hasNext()) {
                    String bug = bugIt.next();
                    if (updateBugs.contains(bug)) {
                        bugIt.remove();
                        updateBugs.remove(bug);
                    }
                }
            }

            if (baseTypeMap.isEmpty()) {
                clsIt.remove();
            }
            if (updateTypeMap.isEmpty()) {
                updateData.remove(clsName);
            }
        }
    }
}
