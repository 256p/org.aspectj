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

 
 package org.aspectj.ajdt.internal.compiler.problem;

import java.lang.reflect.Modifier;
import java.util.Iterator;

import org.aspectj.ajdt.internal.compiler.ast.PointcutDeclaration;
import org.aspectj.ajdt.internal.compiler.ast.Proceed;
import org.aspectj.ajdt.internal.compiler.lookup.EclipseFactory;
import org.aspectj.org.eclipse.jdt.core.compiler.CharOperation;
import org.aspectj.org.eclipse.jdt.core.compiler.IProblem;
import org.aspectj.org.eclipse.jdt.internal.compiler.CompilationResult;
import org.aspectj.org.eclipse.jdt.internal.compiler.IErrorHandlingPolicy;
import org.aspectj.org.eclipse.jdt.internal.compiler.IProblemFactory;
import org.aspectj.org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.aspectj.org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration;
import org.aspectj.org.eclipse.jdt.internal.compiler.ast.ExplicitConstructorCall;
import org.aspectj.org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.aspectj.org.eclipse.jdt.internal.compiler.impl.ReferenceContext;
import org.aspectj.org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.aspectj.org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding;
import org.aspectj.org.eclipse.jdt.internal.compiler.lookup.SourceTypeBinding;
import org.aspectj.org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.aspectj.org.eclipse.jdt.internal.compiler.problem.ProblemReporter;
import org.aspectj.util.FuzzyBoolean;
import org.aspectj.weaver.AjcMemberMaker;
import org.aspectj.weaver.ConcreteTypeMunger;
import org.aspectj.weaver.ResolvedMember;
import org.aspectj.weaver.ResolvedTypeX;
import org.aspectj.weaver.Shadow;
import org.aspectj.weaver.patterns.DeclareSoft;

/**
 * Extends problem reporter to support compiler-side implementation of declare soft. 
 * Also overrides error reporting for the need to implement abstract methods to
 * account for inter-type declarations and pointcut declarations.  This second
 * job might be better done directly in the SourceTypeBinding/ClassScope classes.
 * 
 * @author Jim Hugunin
 */
public class AjProblemReporter extends ProblemReporter {
    
	private static final boolean DUMP_STACK = false;
	
	public EclipseFactory factory;

	public AjProblemReporter(
		IErrorHandlingPolicy policy,
		CompilerOptions options,
		IProblemFactory problemFactory) {
		super(policy, options, problemFactory);
	}
	
	

	public void unhandledException(
		TypeBinding exceptionType,
		ASTNode location)
	{
		if (!factory.getWorld().getDeclareSoft().isEmpty()) {
			Shadow callSite = factory.makeShadow(location, referenceContext);
			Shadow enclosingExec = factory.makeShadow(referenceContext);
			// PR 72157 - calls to super / this within a constructor are not part of the cons join point.
			if ((callSite == null) && (enclosingExec.getKind() == Shadow.ConstructorExecution)
			        && (location instanceof ExplicitConstructorCall)) {
				super.unhandledException(exceptionType, location);
				return;
			}
//			System.err.println("about to show error for unhandled exception: "  + new String(exceptionType.sourceName()) + 
//					" at " + location + " in " + referenceContext);		
			
			for (Iterator i = factory.getWorld().getDeclareSoft().iterator(); i.hasNext(); ) {
				DeclareSoft d = (DeclareSoft)i.next();
				// We need the exceptionType to match the type in the declare soft statement
				// This means it must either be the same type or a subtype
				ResolvedTypeX throwException = factory.fromEclipse((ReferenceBinding)exceptionType);
				FuzzyBoolean isExceptionTypeOrSubtype = 
					d.getException().matchesInstanceof(throwException);
				if (!isExceptionTypeOrSubtype.alwaysTrue() ) continue;

				if (callSite != null) {
					FuzzyBoolean match = d.getPointcut().match(callSite);
					if (match.alwaysTrue()) {
						//System.err.println("matched callSite: "  + callSite + " with " + d);
						return;
					} else if (!match.alwaysFalse()) {
						//!!! need this check to happen much sooner
						//throw new RuntimeException("unimplemented, shouldn't have fuzzy match here");
					}
				}
				if (enclosingExec != null) {
					FuzzyBoolean match = d.getPointcut().match(enclosingExec);
					if (match.alwaysTrue()) {
						//System.err.println("matched enclosingExec: "  + enclosingExec + " with " + d);
						return;
					} else if (!match.alwaysFalse()) {
						//!!! need this check to happen much sooner
						//throw new RuntimeException("unimplemented, shouldn't have fuzzy match here");
					}
				}
			}
		}
		
		//??? is this always correct
		if (location instanceof Proceed) {
			return;
		}

		super.unhandledException(exceptionType, location);
	}

	private boolean isPointcutDeclaration(MethodBinding binding) {
		return CharOperation.prefixEquals(PointcutDeclaration.mangledPrefix, binding.selector);
	}

	public void abstractMethodCannotBeOverridden(
		SourceTypeBinding type,
		MethodBinding concreteMethod)
	{
		if (isPointcutDeclaration(concreteMethod)) {
			return;
		}
		super.abstractMethodCannotBeOverridden(type, concreteMethod);
	}


	public void inheritedMethodReducesVisibility(SourceTypeBinding type, MethodBinding concreteMethod, MethodBinding[] abstractMethods) {
		// if we implemented this method by a public inter-type declaration, then there is no error
		
		ResolvedTypeX onTypeX = null;		
		// If the type is anonymous, look at its supertype
		if (!type.isAnonymousType()) {
			onTypeX = factory.fromEclipse(type);
		} else {
			// Hmmm. If the ITD is on an interface that is being 'instantiated' using an anonymous type,
			// we sort it out elsewhere and don't come into this method - 
			// so we don't have to worry about interfaces, just the superclass.
		    onTypeX = factory.fromEclipse(type.superclass()); //abstractMethod.declaringClass);
		}
		for (Iterator i = onTypeX.getInterTypeMungersIncludingSupers().iterator(); i.hasNext(); ) {
			ConcreteTypeMunger m = (ConcreteTypeMunger)i.next();
			ResolvedMember sig = m.getSignature();
            if (!Modifier.isAbstract(sig.getModifiers())) {
				if (ResolvedTypeX
					.matches(
						AjcMemberMaker.interMethod(
							sig,
							m.getAspectType(),
							sig.getDeclaringType().isInterface(
								factory.getWorld())),
						EclipseFactory.makeResolvedMember(concreteMethod))) {
					return;
				}
			}
		}

		super.inheritedMethodReducesVisibility(type,concreteMethod,abstractMethods);
	}

	public void abstractMethodMustBeImplemented(
		SourceTypeBinding type,
		MethodBinding abstractMethod)
	{
		// if this is a PointcutDeclaration then there is no error
		if (isPointcutDeclaration(abstractMethod)) {
			return;
		}
		
		if (CharOperation.prefixEquals("ajc$interField".toCharArray(), abstractMethod.selector)) {
			//??? think through how this could go wrong
			return;
		}
		
		// if we implemented this method by an inter-type declaration, then there is no error
		//??? be sure this is always right
		ResolvedTypeX onTypeX = null;
		
		// If the type is anonymous, look at its supertype
		if (!type.isAnonymousType()) {
			onTypeX = factory.fromEclipse(type);
		} else {
			// Hmmm. If the ITD is on an interface that is being 'instantiated' using an anonymous type,
			// we sort it out elsewhere and don't come into this method - 
			// so we don't have to worry about interfaces, just the superclass.
		    onTypeX = factory.fromEclipse(type.superclass()); //abstractMethod.declaringClass);
		}
		for (Iterator i = onTypeX.getInterTypeMungersIncludingSupers().iterator(); i.hasNext(); ) {
			ConcreteTypeMunger m = (ConcreteTypeMunger)i.next();
			ResolvedMember sig = m.getSignature();
            if (!Modifier.isAbstract(sig.getModifiers())) {
				if (ResolvedTypeX
					.matches(
						AjcMemberMaker.interMethod(
							sig,
							m.getAspectType(),
							sig.getDeclaringType().isInterface(
								factory.getWorld())),
						EclipseFactory.makeResolvedMember(abstractMethod))) {
					return;
				}
			}
		}

		super.abstractMethodMustBeImplemented(type, abstractMethod);
	}

	public void handle(
		int problemId,
		String[] problemArguments,
		String[] messageArguments,
		int severity,
		int problemStartPosition,
		int problemEndPosition,
		ReferenceContext referenceContext,
		CompilationResult unitResult)
	{
		if (severity != Ignore && DUMP_STACK) {
			Thread.dumpStack();
		}
		super.handle(
			problemId,
			problemArguments,
			messageArguments,
			severity,
			problemStartPosition,
			problemEndPosition,
			referenceContext,
			unitResult);
	}
    


    // PR71076
    public void javadocMissingParamTag(char[] name, int sourceStart, int sourceEnd, int modifiers) {
        boolean reportIt = true;
        String sName = new String(name);
        if (sName.startsWith("ajc$")) reportIt = false;
        if (sName.equals("thisJoinPoint")) reportIt = false;
        if (sName.equals("thisJoinPointStaticPart")) reportIt = false;
        if (sName.equals("thisEnclosingJoinPointStaticPart")) reportIt = false;
        if (sName.equals("ajc_aroundClosure")) reportIt = false;
        if (reportIt) 
        	super.javadocMissingParamTag(name,sourceStart,sourceEnd,modifiers);
    }
    
    public void abstractMethodInAbstractClass(SourceTypeBinding type, AbstractMethodDeclaration methodDecl) {

    	String abstractMethodName = new String(methodDecl.selector);
    	if (abstractMethodName.startsWith("ajc$pointcut")) {
    		// This will already have been reported, see: PointcutDeclaration.postParse()
    		return;
    	}
    	String[] arguments = new String[] {new String(type.sourceName()), abstractMethodName};
    	super.handle(
    		IProblem.AbstractMethodInAbstractClass,
    		arguments,
    		arguments,
    		methodDecl.sourceStart,
    		methodDecl.sourceEnd,this.referenceContext, 
			this.referenceContext == null ? null : this.referenceContext.compilationResult());
    }

}
