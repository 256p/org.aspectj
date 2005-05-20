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


package org.aspectj.weaver.bcel;

import java.util.Iterator;
import java.util.List;

import org.aspectj.apache.bcel.classfile.Attribute;
import org.aspectj.apache.bcel.classfile.Field;
import org.aspectj.apache.bcel.classfile.Synthetic;
import org.aspectj.apache.bcel.classfile.annotation.Annotation;
import org.aspectj.weaver.AjAttribute;
import org.aspectj.weaver.AnnotationX;
import org.aspectj.weaver.BCException;
import org.aspectj.weaver.ResolvedMember;
import org.aspectj.weaver.ResolvedTypeX;
import org.aspectj.weaver.TypeX;
import org.aspectj.weaver.World;

final class BcelField extends ResolvedMember {

	private Field field;
	private boolean isAjSynthetic;
	private boolean isSynthetic = false;
	private ResolvedTypeX[] annotationTypes;
	private AnnotationX[] annotations;
	private World world;

	BcelField(BcelObjectType declaringType, Field field) {
		super(
			FIELD, 
			declaringType.getResolvedTypeX(),
			field.getAccessFlags(),
			field.getName(), 
			field.getSignature());
		this.field = field;
		this.world = declaringType.getResolvedTypeX().getWorld();
		unpackAttributes(world);
		checkedExceptions = TypeX.NONE;
	}

	// ----
	
	private void unpackAttributes(World world) {
		Attribute[] attrs = field.getAttributes();
        List as = BcelAttributes.readAjAttributes(getDeclaringType().getClassName(),attrs, getSourceContext(world),world.getMessageHandler());
        as.addAll(AtAjAttributes.readAj5FieldAttributes(field, world.resolve(getDeclaringType()), getSourceContext(world), world.getMessageHandler()));

		for (Iterator iter = as.iterator(); iter.hasNext();) {
			AjAttribute a = (AjAttribute) iter.next();
			if (a instanceof AjAttribute.AjSynthetic) {
				isAjSynthetic = true;
			} else {
				throw new BCException("weird field attribute " + a);
			}
		}
		isAjSynthetic = false;
		
		
		for (int i = attrs.length - 1; i >= 0; i--) {
			if (attrs[i] instanceof Synthetic) isSynthetic = true;
		}
	}
	
	

	public boolean isAjSynthetic() {
		return isAjSynthetic; // || getName().startsWith(NameMangler.PREFIX);
	}
	
	public boolean isSynthetic() {
		return isSynthetic;
	}
	
	public boolean hasAnnotation(TypeX ofType) {
		Annotation[] anns = field.getAnnotations();
		for (int i = 0; i < anns.length; i++) {
			Annotation annotation = anns[i];
			if (annotation.getTypeName().equals(ofType.getName())) return true;
		}
		return false;
	}
	
	public ResolvedTypeX[] getAnnotationTypes() {
		ensureAnnotationTypesRetrieved();
	 	return annotationTypes;
    }
    
	private void ensureAnnotationTypesRetrieved() {
		if (annotationTypes == null) {
    		Annotation annos[] = field.getAnnotations();
    		annotationTypes = new ResolvedTypeX[annos.length];
    		annotations = new AnnotationX[annos.length];
    		for (int i = 0; i < annos.length; i++) {
				Annotation annotation = annos[i];
				ResolvedTypeX rtx = world.resolve(TypeX.forName(annotation.getTypeName()));
				annotationTypes[i] = rtx;
				annotations[i] = new AnnotationX(annotation,world);
			}
    	}
	}
    
	 public void addAnnotation(AnnotationX annotation) {
	    ensureAnnotationTypesRetrieved();	
		// Add it to the set of annotations
		int len = annotations.length;
		AnnotationX[] ret = new AnnotationX[len+1];
		System.arraycopy(annotations, 0, ret, 0, len);
		ret[len] = annotation;
		annotations = ret;
		
		// Add it to the set of annotation types
		len = annotationTypes.length;
		ResolvedTypeX[] ret2 = new ResolvedTypeX[len+1];
		System.arraycopy(annotationTypes,0,ret2,0,len);
		ret2[len] =world.resolve(TypeX.forName(annotation.getTypeName()));
		annotationTypes = ret2;
		// FIXME asc this call here suggests we are managing the annotations at
		// too many levels, here in BcelField we keep a set and in the lower 'field'
		// object we keep a set - we should think about reducing this to one
		// level??
		field.addAnnotation(annotation.getBcelAnnotation());
	}
}