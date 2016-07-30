/*
 * Copyright (c) 2016, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.llvm.nodes.impl.base;

import java.util.Arrays;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.llvm.nodes.base.LLVMNode;
import com.oracle.truffle.llvm.nodes.impl.func.LLVMFunctionStartNode;

/**
 * This node represents a basic block in LLVM. The node contains both sequential statements which do
 * not change the control flow and terminator instructions which let the function return or continue
 * with another basic block.
 *
 * @see <a href="http://llvm.org/docs/LangRef.html#functions">basic blocks in LLVM IR</a>
 */
public class LLVMBasicBlockNode extends LLVMNode {

    private static final String FORMAT_STRING = "basic block %s (#statements: %s, successors: %s)";
    public static final int DEFAULT_SUCCESSOR = 0;

    @Children private final LLVMNode[] statements;
    @Child private LLVMTerminatorNode termInstruction;

    @CompilationFinal private final long[] successorCount;
    @CompilationFinal private long totalExecutionCount = 0;
    private final int blockId;

    private final BranchProfile controlFlowExceptionProfile = BranchProfile.create();

    @CompilationFinal private SourceSection sourceSection;

    @Override
    public void executeVoid(VirtualFrame frame) {
        executeGetSuccessorIndex(frame);
    }

    public LLVMBasicBlockNode(LLVMNode[] statements, LLVMTerminatorNode termInstruction, int blockId) {
        this.statements = statements;
        this.termInstruction = termInstruction;
        this.blockId = blockId;
        successorCount = new long[termInstruction.getSuccessors().length];
    }

    @ExplodeLoop
    public int executeGetSuccessorIndex(VirtualFrame frame) {
        for (LLVMNode statement : statements) {
            try {
                statement.executeVoid(frame);
            } catch (ControlFlowException e) {
                controlFlowExceptionProfile.enter();
                throw e;
            } catch (RuntimeException e) {
                CompilerDirectives.transferToInterpreter();
                SourceSection exceptionSourceSection = statement.getEncapsulatingSourceSection();
                if (exceptionSourceSection == null) {
                    throw e;
                } else {
                    String message = String.format("LLVM error in %s in %s", exceptionSourceSection.getIdentifier(), exceptionSourceSection.getSource().getName());
                    throw new RuntimeException(message, e);
                }
            }
        }
        return termInstruction.executeGetSuccessorIndex(frame);
    }

    private void incrementTotalCount() {
        if (totalExecutionCount != Long.MAX_VALUE) {
            totalExecutionCount++;
        }
    }

    private void incrementSuccessorCount(int successorIndex) {
        long currentCount = successorCount[successorIndex];
        if (currentCount != Long.MAX_VALUE && totalExecutionCount != Long.MAX_VALUE) {
            successorCount[successorIndex]++;
        }
    }

    /**
     * Gets an array containing the potential successor basic blocks. During execution,
     * {@link #executeGetSuccessorIndex(VirtualFrame)} method returns an index into this array.
     *
     * @return the successors
     */
    public int[] getSuccessors() {
        return termInstruction.getSuccessors();
    }

    /**
     * Gets the branch probability of the given successor.
     *
     * @param successorIndex
     * @return the probability between 0 and 1
     */
    public double getBranchProbability(int successorIndex) {
        double successorBranchProbability;
        if (successorCount[successorIndex] == 0) {
            successorBranchProbability = 0;
        } else {
            successorBranchProbability = (double) successorCount[successorIndex] / totalExecutionCount;
        }
        assert successorBranchProbability >= 0 && successorBranchProbability <= 1;
        return successorBranchProbability;
    }

    public void increaseBranchProbabilityDeoptIfZero(int successorIndex) {
        if (successorCount[successorIndex] == 0) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
        }
        if (CompilerDirectives.inInterpreter()) {
            incrementSuccessorCount(successorIndex);
            incrementTotalCount();
        }
    }

    @Override
    public SourceSection getSourceSection() {
        if (sourceSection == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            // No harm in racing to create the source section
            LLVMFunctionStartNode functionStartNode = NodeUtil.findParent(getParent(), LLVMFunctionStartNode.class);
            assert functionStartNode != null;
            String identifier;
            if (blockId == 0) {
                identifier = String.format("first basic block in function %s", functionStartNode.getFunctionName());
            } else {
                identifier = String.format("basic block %d in function %s", blockId, functionStartNode.getFunctionName());
            }
            sourceSection = functionStartNode.getSourceSection().getSource().createSection(identifier, 1);
        }
        return sourceSection;
    }

    @Override
    public String toString() {
        return String.format(FORMAT_STRING, blockId, statements.length, Arrays.toString(termInstruction.getSuccessors()));
    }

    public int getBlockId() {
        return blockId;
    }

}
