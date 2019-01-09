/*
 * fbdeltatask - a command line tool for finding difference in findbugs results files
 * Copyright 2016-2019 MeBigFatGuy.com
 * Copyright 2016-2019 Dave Brosius
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

public class ReportContentHandler extends DefaultHandler {

    private Map<String, Map<String, Set<String>>> report = new HashMap<>();
    private String bugType;
    private String className;
    private String bugData;
    private boolean inClass;

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
        switch (localName) {
            case "BugInstance":
                bugType = attributes.getValue("type");
            break;

            case "Class":
                className = attributes.getValue("classname");
                inClass = true;
            break;

            case "Method":
            case "Field":
                bugData = attributes.getValue("name") + attributes.getValue("signature");
            break;

            case "SourceLine":
                if (inClass && (bugData == null)) {
                    bugData = attributes.getValue("start") + "-" + attributes.getValue("end");
                }
            break;
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        switch (localName) {
            case "BugInstance":

                Map<String, Set<String>> classBugs = report.get(className);
                if (classBugs == null) {
                    classBugs = new TreeMap<>();
                    report.put(className, classBugs);
                }

                Set<String> bugs = classBugs.get(bugType);
                if (bugs == null) {
                    bugs = new HashSet<>();
                    classBugs.put(bugType, bugs);
                }

                if (bugData == null) {
                    bugData = "UNKNOWN " + Math.random();
                }
                bugs.add(bugData);

                bugType = null;
                className = null;
                bugData = null;
            break;

            case "Class":
                inClass = false;
            break;
        }
    }

    public Map<String, Map<String, Set<String>>> getReport() {
        return report;
    }
}