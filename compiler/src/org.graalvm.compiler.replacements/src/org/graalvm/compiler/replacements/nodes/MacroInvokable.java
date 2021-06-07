/*
 * Copyright (c) 2013, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.compiler.replacements.nodes;

import org.graalvm.compiler.api.replacements.MethodSubstitution;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.graph.NodeInputList;
import org.graalvm.compiler.nodes.CallTargetNode;
import org.graalvm.compiler.nodes.Invokable;
import org.graalvm.compiler.nodes.InvokeNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.spi.LoweringTool;
import org.graalvm.compiler.phases.common.CanonicalizerPhase;
import org.graalvm.compiler.phases.common.FrameStateAssignmentPhase;
import org.graalvm.compiler.phases.common.GuardLoweringPhase;
import org.graalvm.compiler.phases.common.LoweringPhase;
import org.graalvm.compiler.phases.common.RemoveValueProxyPhase;

import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * Macro invokable nodes can be used to temporarily replace an invoke. They can, for example, be
 * used to implement constant folding for known JDK functions like {@link Class#isInterface()}.<br/>
 * <br/>
 * During lowering, multiple sources are queried in order to look for a replacement:
 * <ul>
 * <li>If {@link #getLoweredSnippetGraph(LoweringTool)} returns a non-null result, this graph is
 * used as a replacement.</li>
 * <li>If a {@link MethodSubstitution} for the target method is found, this substitution is used as
 * a replacement.</li>
 * <li>Otherwise, the macro node is replaced with an {@link InvokeNode}. Note that this is only
 * possible if the macro node is a {@link MacroStateSplitNode}.</li>
 * </ul>
 */
public interface MacroInvokable extends Invokable {

    CallTargetNode.InvokeKind getInvokeKind();

    /**
     * Gets the arguments for this macro node.
     */
    NodeInputList<ValueNode> getArguments();

    /**
     * @see #getArguments()
     */
    default ValueNode getArgument(int index) {
        return getArguments().get(index);
    }

    /**
     * @see #getArguments()
     */
    default int getArgumentCount() {
        return getArguments().size();
    }

    static boolean assertArgumentCount(MacroInvokable macro) {
        ResolvedJavaMethod method = macro.getTargetMethod();
        assert method.getSignature().getParameterCount(!method.isStatic()) == macro.getArgumentCount();
        return true;
    }


    /**
     * Gets a snippet to be used for lowering this macro node. The returned graph (if non-null) must
     * have been
     * {@linkplain MacroInvokable#lowerReplacement(StructuredGraph, StructuredGraph, LoweringTool)
     * lowered}.
     */
    @SuppressWarnings("unused")
    default StructuredGraph getLoweredSnippetGraph(LoweringTool tool) {
        return null;
    }

    /**
     * Applies {@linkplain LoweringPhase lowering} to a replacement graph.
     *
     * @param replacementGraph a replacement (i.e., snippet or method substitution) graph
     */
    @SuppressWarnings("try")
    static StructuredGraph lowerReplacement(StructuredGraph graph, StructuredGraph replacementGraph, LoweringTool tool) {
        if (graph.isAfterStage(StructuredGraph.StageFlag.VALUE_PROXY_REMOVAL)) {
            new RemoveValueProxyPhase().apply(replacementGraph);
        }
        StructuredGraph.GuardsStage guardsStage = graph.getGuardsStage();
        if (!guardsStage.allowsFloatingGuards()) {
            new GuardLoweringPhase().apply(replacementGraph, null);
            if (guardsStage.areFrameStatesAtDeopts()) {
                new FrameStateAssignmentPhase().apply(replacementGraph);
            }
        }
        DebugContext debug = replacementGraph.getDebug();
        try (DebugContext.Scope s = debug.scope("LoweringSnippetTemplate", replacementGraph)) {
            new LoweringPhase(CanonicalizerPhase.create(), tool.getLoweringStage()).apply(replacementGraph, tool);
        } catch (Throwable e) {
            throw debug.handle(e);
        }
        return replacementGraph;
    }

}
