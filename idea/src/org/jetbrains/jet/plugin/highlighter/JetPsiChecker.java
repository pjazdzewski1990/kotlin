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

package org.jetbrains.jet.plugin.highlighter;

import com.google.common.collect.Sets;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.lang.annotation.Annotation;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.MultiRangeReference;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;
import org.jetbrains.jet.lang.diagnostics.*;
import org.jetbrains.jet.lang.psi.JetFile;
import org.jetbrains.jet.lang.psi.JetReferenceExpression;
import org.jetbrains.jet.lang.resolve.BindingContext;
import org.jetbrains.jet.plugin.project.WholeProjectAnalyzerFacade;
import org.jetbrains.jet.plugin.quickfix.JetIntentionActionFactory;
import org.jetbrains.jet.plugin.quickfix.QuickFixes;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * @author abreslav
 */
public class JetPsiChecker implements Annotator {
    private static volatile boolean errorReportingEnabled = true;
    private static boolean namesHighlightingTest;

    public static void setErrorReportingEnabled(boolean value) {
        errorReportingEnabled = value;
    }

    public static boolean isErrorReportingEnabled() {
        return errorReportingEnabled;
    }

    @TestOnly
    public static void setNamesHighlightingTest(boolean namesHighlightingTest) {
        JetPsiChecker.namesHighlightingTest = namesHighlightingTest;
    }

    static boolean isNamesHighlightingEnabled() {
        return !ApplicationManager.getApplication().isUnitTestMode() || namesHighlightingTest;
    }

    static void highlightName(@NotNull AnnotationHolder holder,
                              @NotNull PsiElement psiElement,
                              @NotNull TextAttributesKey attributesKey) {
        if (isNamesHighlightingEnabled()) {
            holder.createInfoAnnotation(psiElement, null).setTextAttributes(attributesKey);
        }
    }

    private static HighlightingVisitor[] getBeforeAnalysisVisitors(AnnotationHolder holder) {
        return new HighlightingVisitor[]{
            new SoftKeywordsHighlightingVisitor(holder),
            new LabelsHighlightingVisitor(holder),
            new TypeKindHighlightingVisitor(holder),
        };
    }

    private static HighlightingVisitor[] getAfterAnalysisVisitor(AnnotationHolder holder, BindingContext bindingContext) {
        return new AfterAnalysisHighlightingVisitor[]{
            new PropertiesHighlightingVisitor(holder, bindingContext),
            new FunctionsHighlightingVisitor(holder, bindingContext),
            new VariablesHighlightingVisitor(holder, bindingContext),
        };
    }

    @Override
    public void annotate(@NotNull PsiElement element, @NotNull final AnnotationHolder holder) {
        for (HighlightingVisitor visitor : getBeforeAnalysisVisitors(holder)) {
            element.accept(visitor);
        }

        if (element instanceof JetFile) {
            JetFile file = (JetFile)element;

            try {
                BindingContext bindingContext = WholeProjectAnalyzerFacade.analyzeProjectWithCacheOnAFile(file).getBindingContext();

                if (errorReportingEnabled) {
                    Collection<Diagnostic> diagnostics = Sets.newLinkedHashSet(bindingContext.getDiagnostics());
                    Set<PsiElement> redeclarations = Sets.newHashSet();
                    for (Diagnostic diagnostic : diagnostics) {
                        // This is needed because we have the same context for all files
                        if (diagnostic.getPsiFile() != file) continue;

                        registerDiagnosticAnnotations(diagnostic, redeclarations, holder);
                    }
                }

                for (HighlightingVisitor visitor : getAfterAnalysisVisitor(holder, bindingContext)) {
                    file.acceptChildren(visitor);
                }
            }
            catch (ProcessCanceledException e) {
                throw e;
            }
            catch (AssertionError e) {
                // For failing tests and to notify about idea internal error in -ea mode
                holder.createErrorAnnotation(element, e.getClass().getCanonicalName() + ": " + e.getMessage());
                throw e;
            }
            catch (Throwable e) {
                // TODO
                holder.createErrorAnnotation(element, e.getClass().getCanonicalName() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private static void registerDiagnosticAnnotations(@NotNull Diagnostic diagnostic,
                                                      @NotNull Set<PsiElement> redeclarations,
                                                      @NotNull final AnnotationHolder holder) {
        List<TextRange> textRanges = diagnostic.getTextRanges();
        if (diagnostic.getSeverity() == Severity.ERROR) {
            if (diagnostic.getFactory() == Errors.UNRESOLVED_IDE_TEMPLATE) {
                return;
            }
            if (diagnostic instanceof UnresolvedReferenceDiagnostic) {
                UnresolvedReferenceDiagnostic unresolvedReferenceDiagnostic = (UnresolvedReferenceDiagnostic)diagnostic;
                JetReferenceExpression referenceExpression = unresolvedReferenceDiagnostic.getPsiElement();
                PsiReference reference = referenceExpression.getReference();
                if (reference instanceof MultiRangeReference) {
                    MultiRangeReference mrr = (MultiRangeReference)reference;
                    for (TextRange range : mrr.getRanges()) {
                        Annotation annotation = holder.createErrorAnnotation(
                            range.shiftRight(referenceExpression.getTextOffset()),
                            diagnostic.getMessage());

                        registerQuickFix(annotation, diagnostic);

                        annotation.setHighlightType(ProblemHighlightType.LIKE_UNKNOWN_SYMBOL);
                    }
                }
                else {
                    for (TextRange textRange : textRanges) {
                        Annotation annotation = holder.createErrorAnnotation(textRange, diagnostic.getMessage());
                        registerQuickFix(annotation, diagnostic);
                        annotation.setHighlightType(ProblemHighlightType.LIKE_UNKNOWN_SYMBOL);
                    }
                }

                return;
            }

            if (diagnostic.getFactory() == Errors.ILLEGAL_ESCAPE_SEQUENCE) {
                for (TextRange textRange : diagnostic.getTextRanges()) {
                    Annotation annotation = holder.createErrorAnnotation(textRange, diagnostic.getMessage());
                    annotation.setTextAttributes(JetHighlightingColors.INVALID_STRING_ESCAPE);
                }
            }

            if (diagnostic instanceof RedeclarationDiagnostic) {
                RedeclarationDiagnostic redeclarationDiagnostic = (RedeclarationDiagnostic)diagnostic;
                registerQuickFix(markRedeclaration(redeclarations, redeclarationDiagnostic, holder), diagnostic);
                return;
            }

            // Generic annotation
            for (TextRange textRange : textRanges) {
                Annotation errorAnnotation = holder.createErrorAnnotation(textRange, getMessage(diagnostic));
                registerQuickFix(errorAnnotation, diagnostic);

                if (diagnostic.getFactory() == Errors.INVISIBLE_REFERENCE) {
                    errorAnnotation.setHighlightType(ProblemHighlightType.LIKE_UNKNOWN_SYMBOL);
                }
            }
        }
        else if (diagnostic.getSeverity() == Severity.WARNING) {
            for (TextRange textRange : textRanges) {
                Annotation annotation = holder.createWarningAnnotation(textRange, getMessage(diagnostic));
                registerQuickFix(annotation, diagnostic);

                if (diagnostic.getFactory() instanceof UnusedElementDiagnosticFactory) {
                    annotation.setHighlightType(ProblemHighlightType.LIKE_UNUSED_SYMBOL);
                }
            }
        }
    }

    /*
     * Add a quick fix if and return modified annotation.
     */
    @Nullable
    private static Annotation registerQuickFix(@Nullable Annotation annotation, @NotNull Diagnostic diagnostic) {
        if (annotation == null) {
            return null;
        }

        Collection<JetIntentionActionFactory> intentionActionFactories = QuickFixes.getActionFactories(diagnostic.getFactory());
        for (JetIntentionActionFactory intentionActionFactory : intentionActionFactories) {
            IntentionAction action = null;
            if (intentionActionFactory != null) {
                action = intentionActionFactory.createAction(diagnostic);
            }
            if (action != null) {
                annotation.registerFix(action);
            }
        }

        Collection<IntentionAction> actions = QuickFixes.getActions(diagnostic.getFactory());
        for (IntentionAction action : actions) {
            annotation.registerFix(action);
        }

        return annotation;
    }

    @NotNull
    private static String getMessage(@NotNull Diagnostic diagnostic) {
        if (ApplicationManager.getApplication().isInternal() || ApplicationManager.getApplication().isUnitTestMode()) {
            return "[" + diagnostic.getFactory().getName() + "] " + diagnostic.getMessage();
        }
        return diagnostic.getMessage();
    }

    @Nullable
    private static Annotation markRedeclaration(@NotNull Set<PsiElement> redeclarations,
                                                @NotNull RedeclarationDiagnostic diagnostic,
                                                @NotNull AnnotationHolder holder) {
        if (!redeclarations.add(diagnostic.getPsiElement())) return null;
        List<TextRange> textRanges = diagnostic.getTextRanges();
        if (textRanges.isEmpty()) return null;
        return holder.createErrorAnnotation(textRanges.get(0), getMessage(diagnostic));
    }
}
