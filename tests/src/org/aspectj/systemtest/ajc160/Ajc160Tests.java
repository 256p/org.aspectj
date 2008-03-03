/*******************************************************************************
 * Copyright (c) 2006 IBM 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Andy Clement - initial API and implementation
 *******************************************************************************/
package org.aspectj.systemtest.ajc160;

import java.io.File;

import org.aspectj.testing.XMLBasedAjcTestCase;
import junit.framework.Test;

/**
 * These are tests for AspectJ1.6.0
 */
public class Ajc160Tests extends org.aspectj.testing.XMLBasedAjcTestCase {
	
	// AspectH1.6.0rc1
	public void testHasMethodAnnoValueInt_various() { runTest("hasmethod anno value - I");}
	public void testHasMethodAnnoValueBoolean_various() { runTest("hasmethod anno value - Z");}
	
	// AspectJ1.6.0m2 and earlier
	public void testBoundsCheckShouldFail_pr219298() { runTest("bounds check failure");}
	public void testBoundsCheckShouldFail_pr219298_2() { runTest("bounds check failure - 2");}
	public void testGenericMethodMatching_pr204505_1() { runTest("generics method matching - 1");}
	public void testGenericMethodMatching_pr204505_2() { runTest("generics method matching - 2");}
	public void testDecFieldProblem_pr218167() { runTest("dec field problem");}
	public void testGenericsSuperITD_pr206911() { runTest("generics super itd"); }
	public void testGenericsSuperITD_pr206911_2() { runTest("generics super itd - 2"); }
	public void testSerializationAnnotationStyle_pr216311() { runTest("serialization and annotation style");}
	public void testDecpRepetition_pr214559() { runTest("decp repetition problem");} // all code in one file
	public void testDecpRepetition_pr214559_2() { runTest("decp repetition problem - 2");} // all code in one file, default package
	public void testDecpRepetition_pr214559_3() { runTest("decp repetition problem - 3");} // across multiple files
	public void testISEAnnotations_pr209831() { runTest("illegal state exception with annotations");}
	public void testISEAnnotations_pr209831_2() { runTest("illegal state exception with annotations - 2");}
	
//  See HasMemberTypePattern.hasMethod()
//	public void testHasMethodSemantics() { runTest("hasmethod semantics"); }

//  See BcelTypeMunger line 786 relating to these
//String sig = interMethodDispatcher.getSignature();BROKE - should get the generic signature here and use that.
//	public void testITDLostGenerics_pr211146() { runTest("itd lost generic signature");}
//	public void testITDLostGenerics_pr211146_2() { runTest("itd lost generic signature - field");}

	/////////////////////////////////////////
  public static Test suite() {
    return XMLBasedAjcTestCase.loadSuite(Ajc160Tests.class);
  }

  protected File getSpecFile() {
    return new File("../tests/src/org/aspectj/systemtest/ajc160/ajc160.xml");
  }
  
}