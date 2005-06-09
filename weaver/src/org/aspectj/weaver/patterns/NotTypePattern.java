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


package org.aspectj.weaver.patterns;

import java.io.DataOutputStream;
import java.io.IOException;

import org.aspectj.util.FuzzyBoolean;
import org.aspectj.weaver.AjAttribute;
import org.aspectj.weaver.ISourceContext;
import org.aspectj.weaver.ResolvedTypeX;
import org.aspectj.weaver.VersionedDataInputStream;

/**
 * !TypePattern
 * 
 * <p>any binding to formals is explicitly forbidden for any composite, ! is
 * just the most obviously wrong case.
 * 
 * @author Erik Hilsdale
 * @author Jim Hugunin
 */
public class NotTypePattern extends TypePattern {
	private TypePattern negatedPattern;
	
	public NotTypePattern(TypePattern pattern) {
		super(false,false);  //??? we override all methods that care about includeSubtypes
		this.negatedPattern = pattern;
		setLocation(pattern.getSourceContext(), pattern.getStart(), pattern.getEnd());
	}

    public TypePattern getNegatedPattern() {
        return negatedPattern;
    }

	/* (non-Javadoc)
	 * @see org.aspectj.weaver.patterns.TypePattern#couldEverMatchSameTypesAs(org.aspectj.weaver.patterns.TypePattern)
	 */
	protected boolean couldEverMatchSameTypesAs(TypePattern other) {
		return true;
	}
	
	public FuzzyBoolean matchesInstanceof(ResolvedTypeX type) {
		return negatedPattern.matchesInstanceof(type).not();
	}

	protected boolean matchesExactly(ResolvedTypeX type) {
		return (!negatedPattern.matchesExactly(type) && annotationPattern.matches(type).alwaysTrue());
	}
	
	protected boolean matchesExactly(ResolvedTypeX type, ResolvedTypeX annotatedType) {
		return (!negatedPattern.matchesExactly(type,annotatedType) && annotationPattern.matches(annotatedType).alwaysTrue());
	}
	
	public boolean matchesStatically(Class type) {
		return !negatedPattern.matchesStatically(type);
	}

	public FuzzyBoolean matchesInstanceof(Class type) {
		return negatedPattern.matchesInstanceof(type).not();
	}

	protected boolean matchesExactly(Class type) {
		return !negatedPattern.matchesExactly(type);
	}
	
	public boolean matchesStatically(ResolvedTypeX type) {
		return !negatedPattern.matchesStatically(type);
	}
	
	public void setAnnotationTypePattern(AnnotationTypePattern annPatt) {
		super.setAnnotationTypePattern(annPatt);
	}
	
	public void setIsVarArgs(boolean isVarArgs) {
		negatedPattern.setIsVarArgs(isVarArgs);
	}
	
	
	public void write(DataOutputStream s) throws IOException {
		s.writeByte(TypePattern.NOT);
		negatedPattern.write(s);
		annotationPattern.write(s);
		writeLocation(s);
	}
	
	public static TypePattern read(VersionedDataInputStream s, ISourceContext context) throws IOException {
		TypePattern ret = new NotTypePattern(TypePattern.read(s, context));
		if (s.getMajorVersion()>=AjAttribute.WeaverVersionInfo.WEAVER_VERSION_MAJOR_AJ150) {
			ret.annotationPattern = AnnotationTypePattern.read(s,context);
		}
		ret.readLocation(context, s);
		return ret;
	}

	public TypePattern resolveBindings(
		IScope scope,
		Bindings bindings,
		boolean allowBinding, boolean requireExactType)
	{
		if (requireExactType) return notExactType(scope);
		negatedPattern = negatedPattern.resolveBindings(scope, bindings, false, false);
		return this;
	}
	
	public TypePattern resolveBindingsFromRTTI(boolean allowBinding, boolean requireExactType) {
		if (requireExactType) return TypePattern.NO;
		negatedPattern = negatedPattern.resolveBindingsFromRTTI(allowBinding,requireExactType);
		return this;
	}

	public String toString() {
		StringBuffer buff = new StringBuffer();
		if (annotationPattern != AnnotationTypePattern.ANY) {
			buff.append('(');
			buff.append(annotationPattern.toString());
			buff.append(' ');
		}
		buff.append('!');
		buff.append(negatedPattern);
		if (annotationPattern != AnnotationTypePattern.ANY) {
			buff.append(')');
		}
		return buff.toString();
	}
	
	/* (non-Javadoc)
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	public boolean equals(Object obj) {
		if (! (obj instanceof NotTypePattern)) return false;
		return (negatedPattern.equals(((NotTypePattern)obj).negatedPattern));
	}
	
	/* (non-Javadoc)
	 * @see java.lang.Object#hashCode()
	 */
	public int hashCode() {
		return 17 + 37 * negatedPattern.hashCode();
	}

    public Object accept(PatternNodeVisitor visitor, Object data) {
        return visitor.visit(this, data);
    }
	
	public Object traverse(PatternNodeVisitor visitor, Object data) {
		Object ret = accept(visitor,data);
		negatedPattern.traverse(visitor, ret);
		return ret;
	}

}
