/*
 * fbdeltatask - a command line tool for finding difference in findbugs results files
 * Copyright 2016 MeBigFatGuy.com
 * Copyright 2016 Dave Brosius
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations
 * under the License.
 */
package com.mebigfatguy.fbdelta;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
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

            getProject().log("[fb-delta] Parsing base report " + baseReport, Project.MSG_VERBOSE);
            Map<String, Map<String, Set<String>>> baseData = parseReport(baseReport);
            getProject().log("[fb-delta] Parsing update report " + updateReport, Project.MSG_VERBOSE);
            Map<String, Map<String, Set<String>>> updateData = parseReport(updateReport);

            getProject().log("[fb-delta] Removing duplicate bugs from base and update reports", Project.MSG_VERBOSE);
            removeDuplicates(baseData, updateData);

            if (outputReport != null) {
                outputReport.getParentFile().mkdirs();
                try (PrintWriter pw = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outputReport), StandardCharsets.UTF_8)))) {
                    getProject().log("[fb-delta] Printing report to " + outputReport, Project.MSG_VERBOSE);
                    pw.println("<fbdelta>");
                    pw.println("\t<fixed>");
                    for (Map.Entry<String, Map<String, Set<String>>> clsEntry : baseData.entrySet()) {
                        pw.println("\t\t<class name='" + clsEntry.getKey() + "'>");
                        for (Map.Entry<String, Set<String>> typeEntry : clsEntry.getValue().entrySet()) {
                            pw.println("\t\t\t<bug type='" + typeEntry.getKey() + "' count='" + typeEntry.getValue().size() + "'/>");
                        }
                        pw.println("\t\t</class>");
                    }
                    pw.println("\t</fixed>");
                    pw.println("\t<new>");
                    for (Map.Entry<String, Map<String, Set<String>>> clsEntry : updateData.entrySet()) {
                        pw.println("\t\t<class name='" + clsEntry.getKey() + "'>");
                        for (Map.Entry<String, Set<String>> typeEntry : clsEntry.getValue().entrySet()) {
                            pw.println("\t\t\t<bug type='" + typeEntry.getKey() + "' count='" + typeEntry.getValue().size() + "'/>");
                        }
                        pw.println("\t\t</class>");
                    }
                    pw.println("\t</new>");
                    pw.println("</fbdelta>");
                }
            }

            if ((changedPropertyName != null) && (!baseData.isEmpty() || !updateData.isEmpty())) {
                getProject().log("[fb-delta] Setting changed property '" + changedPropertyName + "' to TRUE", Project.MSG_VERBOSE);
                getProject().setProperty(changedPropertyName, String.valueOf(Boolean.TRUE));
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
        XPathExpression sourceLineXPE = xp.compile("./Class/SourceLine");

        Map<String, Map<String, Set<String>>> report = new TreeMap<>();

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
                classBugs = new TreeMap<>();
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

                if (baseBugs.isEmpty()) {
                    baseTypeMap.remove(baseType);
                }
                if (updateBugs.isEmpty()) {
                    updateTypeMap.remove(baseType);
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

    /**
     * for debugging only
     */
    public static void main(String[] args) {
        FBDeltaTask t = new FBDeltaTask();
        Project p = new Project();
        t.setProject(p);

        t.setBaseReport(new File("/home/dave/dev/fb-contrib/samples.xml"));
        t.setUpdateReport(new File("/home/dave/dev/fb-contrib/target/samples.xml"));
        t.setChanged("changed");
        t.setOutputReport(new File("/home/dave/dev/fb-contrib/target/samples-delta.xml"));

        t.execute();

    }
}
