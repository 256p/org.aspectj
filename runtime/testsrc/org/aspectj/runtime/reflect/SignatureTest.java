/*******************************************************************************
 * Copyright (c) 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.aspectj.runtime.reflect;

import junit.framework.TestCase;

/**
 */
public class SignatureTest extends TestCase {
	public void testGetDeclaringTypeName() {
		FieldSignatureImpl fsi = new FieldSignatureImpl(0,"x",SignatureTest.class,String.class);
		assertEquals(SignatureTest.class.getName(),fsi.getDeclaringTypeName());
		assertSame(fsi.getDeclaringTypeName(),fsi.getDeclaringTypeName()); // should be cached.
	}
	
	public void testToShortMiddleLongString () {
		MethodSignatureImpl msi = new MethodSignatureImpl(0,"test",SignatureTest.class,new Class[] { String.class, Integer.TYPE }, new String[] { "s", "i" }, new Class[] {}, Runnable.class);
		String shortString = msi.toShortString();
		assertSame(shortString,msi.toShortString()); // should be cached.
		String middleString = msi.toString();
		assertSame(middleString,msi.toString()); // should be cached.
		String longString = msi.toLongString();
		assertSame(longString,msi.toLongString()); // should be cached.
		assertTrue("String representations should be different",!(shortString.equals(middleString) || middleString.equals(longString) || longString.equals(shortString)));
	}
}
