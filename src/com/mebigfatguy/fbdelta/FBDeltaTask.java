/*
 * fbdeltatask - a command line tool for finding difference in findbugs results files
 * Copyright 2016-2018 MeBigFatGuy.com
 * Copyright 2016-2018 Dave Brosius
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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpressionException;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.Task;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLReaderFactory;

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
                throw new BuildException("'baseReport' is not specified or is invalid (" + baseReport + ")");
            }
            if ((updateReport == null) || (!updateReport.isFile())) {
                throw new BuildException("'updateReport' is not specified or is invalid (" + updateReport + ")");
            }

            ExecutorService es = Executors.newFixedThreadPool(2);

            Future<Map<String, Map<String, Set<String>>>> baseFuture = es.submit(new Callable<Map<String, Map<String, Set<String>>>>() {
                @Override
                public Map<String, Map<String, Set<String>>> call() throws IOException, XPathExpressionException, ParserConfigurationException, SAXException {
                    getProject().log("[fb-delta] Parsing base report " + baseReport, Project.MSG_VERBOSE);
                    return parseReport(baseReport);
                }
            });

            Future<Map<String, Map<String, Set<String>>>> updateFuture = es.submit(new Callable<Map<String, Map<String, Set<String>>>>() {
                @Override
                public Map<String, Map<String, Set<String>>> call() throws IOException, XPathExpressionException, ParserConfigurationException, SAXException {
                    getProject().log("[fb-delta] Parsing update report " + updateReport, Project.MSG_VERBOSE);
                    return parseReport(updateReport);
                }
            });

            es.shutdown();

            Map<String, Map<String, Set<String>>> baseData = baseFuture.get();
            Map<String, Map<String, Set<String>>> updateData = updateFuture.get();

            getProject().log("[fb-delta] Removing duplicate bugs from base and update reports", Project.MSG_VERBOSE);
            removeDuplicates(baseData, updateData);

            report(baseData, updateData);

            if ((changedPropertyName != null) && (!baseData.isEmpty() || !updateData.isEmpty())) {
                getProject().log("[fb-delta] Setting changed property '" + changedPropertyName + "' to TRUE", Project.MSG_VERBOSE);
                getProject().setProperty(changedPropertyName, String.valueOf(Boolean.TRUE));
            }

        } catch (Exception e) {
            throw new BuildException("Failed to run fb-delta on report of " + baseReport + " to " + updateReport, e);
        }
    }

    private Map<String, Map<String, Set<String>>> parseReport(File reportFile) throws IOException, SAXException {

        XMLReader r = XMLReaderFactory.createXMLReader();
        ReportContentHandler handler = new ReportContentHandler();
        r.setContentHandler(handler);

        try (InputStream is = new BufferedInputStream(Files.newInputStream(reportFile.toPath()))) {
            r.parse(new InputSource(is));

            return handler.getReport();
        }
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

                Iterator<String> bugIt = baseBugs.iterator();
                while (bugIt.hasNext()) {
                    String bug = bugIt.next();
                    if (updateBugs.contains(bug)) {
                        bugIt.remove();
                        updateBugs.remove(bug);
                    }
                }

                if (baseBugs.isEmpty()) {
                    typeIt.remove();
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

    private void report(Map<String, Map<String, Set<String>>> baseData, Map<String, Map<String, Set<String>>> updateData) throws IOException {
        if (outputReport != null) {
            outputReport.getParentFile().mkdirs();
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outputReport.toPath(), StandardCharsets.UTF_8))) {
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
    }
}
