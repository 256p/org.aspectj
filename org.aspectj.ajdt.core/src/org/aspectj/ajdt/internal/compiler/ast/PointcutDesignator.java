/* *******************************************************************
 * Copyright (c) 2002 Palo Alto Research Center, Incorporated (PARC).
 * All rights reserved. 
 * This program and the accompanying materials are made available 
 * under the terms of the Common Public License v1.0 
 * which accompanies this distribution and is available at 
 * http://www.eclipse.org/legal/cpl-v10.html 
 *  
 * Contributors: 
 *     PARC     initial implementation 
 * ******************************************************************/


package org.aspectj.ajdt.internal.compiler.ast;

import org.aspectj.ajdt.internal.compiler.lookup.EclipseScope;
import org.aspectj.ajdt.internal.compiler.lookup.EclipseFactory;
import org.aspectj.weaver.TypeX;
import org.aspectj.weaver.patterns.FormalBinding;
import org.aspectj.weaver.patterns.Pointcut;
import org.aspectj.org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration;
import org.aspectj.org.eclipse.jdt.internal.compiler.ast.Argument;
import org.aspectj.org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.aspectj.org.eclipse.jdt.internal.compiler.ast.MethodDeclaration;
import org.aspectj.org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.aspectj.org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.aspectj.org.eclipse.jdt.internal.compiler.lookup.SourceTypeBinding;
import org.aspectj.org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.aspectj.org.eclipse.jdt.internal.compiler.parser.Parser;

public class PointcutDesignator extends ASTNode {
	private Pointcut pointcut;
    private PseudoTokens tokens; //XXX redundant
    private boolean isError = false;

	public PointcutDesignator(Parser parser, PseudoTokens tokens) {
		super();
		sourceStart = tokens.sourceStart;
		sourceEnd = tokens.sourceEnd;
		this.tokens = tokens;
		
		Pointcut pc = tokens.parsePointcut(parser);
		if (pc.toString().equals("")) { //??? is this a good signal
			isError = true;
		}
		pointcut = pc;
	}
	
	public void postParse(TypeDeclaration typeDec, MethodDeclaration enclosingDec) {
		tokens.postParse(typeDec, enclosingDec);
	}


	public boolean finishResolveTypes(final AbstractMethodDeclaration dec, MethodBinding method, final int baseArgumentCount, SourceTypeBinding sourceTypeBinding) {
		//System.err.println("resolving: " + this);
		//Thread.currentThread().dumpStack();
		//XXX why do we need this test
		// AMC added concrete too. Needed because declare declarations concretize their
		// shadow mungers early.
		if (pointcut.state == Pointcut.RESOLVED ||
			pointcut.state == Pointcut.CONCRETE) return true;
		
		TypeBinding[] parameters = method.parameters;
		Argument[] arguments = dec.arguments;

        FormalBinding[] bindings = new FormalBinding[baseArgumentCount];
        for (int i = 0, len = baseArgumentCount; i < len; i++) {
            Argument arg = arguments[i];
            String name = new String(arg.name);
            TypeX type = EclipseFactory.fromBinding(parameters[i]);
            bindings[i] = new FormalBinding(type, name, i, arg.sourceStart, arg.sourceEnd, "unknown");
        }
        
        EclipseScope scope = new EclipseScope(bindings, dec.scope);

        pointcut = pointcut.resolve(scope);
        return true;
	}

    public Pointcut getPointcut() {
        return pointcut;
    }
    
	public boolean isError() {
		return isError;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.internal.compiler.ast.ASTNode#print(int, java.lang.StringBuffer)
	 */
	public StringBuffer print(int indent, StringBuffer output) {
		if (pointcut == null) return output.append("<pcd>");
		return output.append(pointcut.toString());
	}

}
