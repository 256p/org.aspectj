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


package org.aspectj.ajdt.ajc;

import org.aspectj.ajdt.internal.core.builder.AjBuildConfig;
import org.aspectj.ajdt.internal.core.builder.AjBuildManager;
import org.aspectj.bridge.AbortException;
import org.aspectj.bridge.CountingMessageHandler;
import org.aspectj.bridge.ICommand;
import org.aspectj.bridge.IMessage;
import org.aspectj.bridge.IMessageHandler;
import org.aspectj.bridge.Message;
import org.aspectj.bridge.MessageUtil;
import org.eclipse.jdt.internal.core.builder.MissingSourceFileException;

/**
 * ICommand adapter for the AspectJ compiler.
 * Not thread-safe.
 */
public class AjdtCommand implements ICommand {
    
    /** Message String for any AbortException thrown from ICommand API's */
    public static final String ABORT_MESSAGE = "ABORT";
    
    private boolean canRepeatCommand = true;
	
	AjBuildManager buildManager = null;
	String[] savedArgs = null;
          
	/**
     * Run AspectJ compiler, wrapping any exceptions thrown as
     * ABORT messages (containing ABORT_MESSAGE String).
     * @param args the String[] for the compiler
     * @param handler the IMessageHandler for any messages
	 * @see org.aspectj.bridge.ICommand#runCommand(String[], IMessageHandler)
     * @return false if handler has errors or the command failed
	 */
	public boolean runCommand(String[] args, IMessageHandler handler) {
		buildManager = new AjBuildManager(handler); 
		savedArgs = new String[args.length];
        System.arraycopy(args, 0, savedArgs, 0, savedArgs.length);
        for (int i = 0; i < args.length; i++) {
            if ("-help".equals(args[i])) {
                // should be info, but handler usually suppresses
                MessageUtil.abort(handler, BuildArgParser.getUsage());
                return true;
            }
        }
        return doCommand(handler, false);
    }

    /**
     * Run AspectJ compiler, wrapping any exceptions thrown as
     * ABORT messages (containing ABORT_MESSAGE String).
     * @param handler the IMessageHandler for any messages
     * @see org.aspectj.bridge.ICommand#repeatCommand(IMessageHandler)
     * @return false if handler has errors or the command failed
     */
	public boolean repeatCommand(IMessageHandler handler) {
		if (null == buildManager) {
            MessageUtil.abort(handler, "repeatCommand called before runCommand");
            return false;            
        }
        return doCommand(handler, true);
    }
    
    /** 
     * Delegate of both runCommand and repeatCommand.
     * This invokes the argument parser each time
     * (even when repeating).
     * If the parser detects errors, this signals an 
     * abort with the usage message and returns false.
     * @param handler the IMessageHandler sink for any messages
     * @param repeat if true, do incremental build, else do batch build
     * @return false if handler has any errors or command failed
     */
    protected boolean doCommand(IMessageHandler handler, boolean repeat) {
        try {
			//buildManager.setMessageHandler(handler);
            CountingMessageHandler counter = new CountingMessageHandler(handler);
            if (counter.hasErrors()) {
                return false;
            }
            // regenerate configuration b/c world might have changed (?)
            AjBuildConfig config = genBuildConfig(savedArgs, counter);
            if (!config.hasSources()) {
                MessageUtil.error(counter, "no sources specified");
            }
            if (counter.hasErrors())  { // print usage for config errors
                String usage = BuildArgParser.getUsage();
                MessageUtil.abort(handler, usage);
                return false;
            }
            //System.err.println("errs: " + counter.hasErrors());          
            return ((repeat 
                        ? buildManager.incrementalBuild(config, handler)
                        : buildManager.batchBuild(config, handler))
                    && !counter.hasErrors());
        } catch (AbortException ae) {
            if (ae.isSilent()) {
                throw ae;
            } else {
                MessageUtil.abort(handler, ABORT_MESSAGE, ae);
            }
        } catch (MissingSourceFileException t) { 
            MessageUtil.error(handler, t.getMessage());
        } catch (Throwable t) {
            MessageUtil.abort(handler, ABORT_MESSAGE, t);
        } 
        return false;
    }

    /** @throws AbortException on error after handling message */
    public static AjBuildConfig genBuildConfig(String[] args, CountingMessageHandler handler) {
        BuildArgParser parser = new BuildArgParser();
        AjBuildConfig config = parser.genBuildConfig(args, handler);
        String message = parser.getOtherMessages(true);

        if (null != message) {
            IMessage.Kind kind = inferKind(message);
            IMessage m = new Message(message, kind, null, null);            
            handler.handleMessage(m);
        }
        
        return config;
    }
    
    /** @return IMessage.WARNING unless message contains error or info */
    protected static IMessage.Kind inferKind(String message) { // XXX dubious
        if (-1 == message.indexOf("error")) {
            return IMessage.ERROR;
        } else if (-1 == message.indexOf("info")) {
            return IMessage.INFO;
        } else {
            return IMessage.WARNING;
        }
    }
}
