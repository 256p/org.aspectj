/*******************************************************************************
 * Copyright (c) 2005 Jonas Bon�r, Alexandre Vasseur
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 *******************************************************************************/
package org.aspectj.lang.annotation;

import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

/**
 * Aspect declaration
 *
 * @author <a href="mailto:alex AT gnilux DOT com">Alexandre Vasseur</a>
 */
@Target(ElementType.TYPE)
public @interface Aspect {

    /**
     * Per clause expression, defaults to singleton aspect
     */
    public String value() default "";//TODO
}
