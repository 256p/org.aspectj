/*******************************************************************************
 * Copyright (c) Jonas Bon�r, Alexandre Vasseur
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 *******************************************************************************/
package org.aspectj.weaver.loadtime.test;

import junit.framework.TestCase;

import java.net.URL;

import org.aspectj.weaver.loadtime.definition.Definition;
import org.aspectj.weaver.loadtime.definition.DocumentParser;

/**
 * @author <a href="mailto:alex AT gnilux DOT com">Alexandre Vasseur</a>
 */
public class DocumentParserTest extends TestCase {

    public void testSimple() throws Throwable {
        URL url = DocumentParserTest.class.getResource("simple.xml");
        Definition def = DocumentParser.parse(url);
        assertEquals("-showWeaveInfo", def.getWeaverOptions().trim());
    }

    public void testSimpleWithDtd() throws Throwable {
        URL url = DocumentParserTest.class.getResource("simpleWithDtd.xml");
        Definition def = DocumentParser.parse(url);
        assertEquals("-showWeaveInfo", def.getWeaverOptions().trim());
        assertTrue(def.getAspectClassNames().contains("test.Aspect"));

        assertEquals("foo..bar.Goo+", def.getIncludePatterns().get(0));
        assertEquals("@Baz", def.getAspectExcludePatterns().get(0));

    }

}
