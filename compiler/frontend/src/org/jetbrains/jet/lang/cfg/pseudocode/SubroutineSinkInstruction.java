/*
 * Copyright 2010-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.lang.cfg.pseudocode;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.jet.lang.psi.JetElement;

import java.util.Collection;
import java.util.Collections;

/**
 * @author svtk
 */
public class SubroutineSinkInstruction extends InstructionImpl {
    private final JetElement subroutine;
    private final String debugLabel;

    public SubroutineSinkInstruction(@NotNull JetElement subroutine, @NotNull String debugLabel) {
        this.subroutine = subroutine;
        this.debugLabel = debugLabel;
    }

    public JetElement getSubroutine() {
        return subroutine;
    }

    @NotNull
    @Override
    public Collection<Instruction> getNextInstructions() {
        return Collections.emptyList();
    }

    @Override
    public void accept(InstructionVisitor visitor) {
        visitor.visitSubroutineSink(this);
    }

    @Override
    public String toString() {
        return debugLabel;
    }
}
