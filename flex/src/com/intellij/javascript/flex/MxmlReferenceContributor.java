package com.intellij.javascript.flex;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.LocalQuickFixProvider;
import com.intellij.javascript.flex.css.FlexCssPropertyDescriptor;
import com.intellij.javascript.flex.mxml.MxmlJSClass;
import com.intellij.javascript.flex.mxml.schema.AnnotationBackedDescriptorImpl;
import com.intellij.javascript.flex.mxml.schema.CodeContext;
import com.intellij.lang.javascript.JavaScriptSupportLoader;
import com.intellij.lang.javascript.flex.AnnotationBackedDescriptor;
import com.intellij.lang.javascript.flex.ReferenceSupport;
import com.intellij.lang.javascript.flex.actions.newfile.CreateFlexComponentFix;
import com.intellij.lang.javascript.flex.actions.newfile.CreateFlexSkinIntention;
import com.intellij.lang.javascript.psi.JSCommonTypeNames;
import com.intellij.lang.javascript.psi.JSFunction;
import com.intellij.lang.javascript.psi.ecmal4.JSAttribute;
import com.intellij.lang.javascript.psi.ecmal4.JSAttributeNameValuePair;
import com.intellij.lang.javascript.psi.ecmal4.impl.JSPackageWrapper;
import com.intellij.lang.javascript.psi.impl.JSReferenceSet;
import com.intellij.lang.javascript.psi.impl.JSTextReference;
import com.intellij.lang.javascript.psi.resolve.JSResolveResult;
import com.intellij.lang.javascript.psi.resolve.ResultSink;
import com.intellij.lang.javascript.validation.fixes.CreateClassIntentionWithCallback;
import com.intellij.lang.javascript.validation.fixes.CreateClassOrInterfaceFix;
import com.intellij.lang.javascript.validation.fixes.CreateFlexMobileViewIntentionAndFix;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.patterns.PatternCondition;
import com.intellij.patterns.XmlPatterns;
import com.intellij.psi.*;
import com.intellij.psi.css.impl.util.CssReferenceProviderUtil;
import com.intellij.psi.filters.*;
import com.intellij.psi.filters.position.FilterPattern;
import com.intellij.psi.filters.position.NamespaceFilter;
import com.intellij.psi.filters.position.ParentElementFilter;
import com.intellij.psi.impl.source.resolve.reference.impl.providers.AttributeValueSelfReference;
import com.intellij.psi.meta.PsiMetaData;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.*;
import com.intellij.util.*;
import com.intellij.util.text.StringTokenizer;
import com.intellij.xml.XmlAttributeDescriptor;
import com.intellij.xml.util.XmlTagUtil;
import com.intellij.xml.util.XmlUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.intellij.patterns.StandardPatterns.or;
import static com.intellij.patterns.StandardPatterns.string;
import static com.intellij.patterns.XmlPatterns.xmlAttribute;

public class MxmlReferenceContributor extends PsiReferenceContributor {
  private static final String STYLE_NAME_ATTR_SUFFIX = "StyleName";
  private static final String STYLE_NAME_ATTR = "styleName";
  private static final String BINDING_TAG_NAME = "Binding";
  private static final String FORMAT_ATTR_NAME = "format";
  private static final String FILE_ATTR_VALUE = "File";
  private static final String SKIN_CLASS_ATTR_NAME = "skinClass";
  private static final String UI_COMPONENT_FQN = "mx.core.UIComponent";

  @Override
  public void registerReferenceProviders(final @NotNull PsiReferenceRegistrar registrar) {
    registrar.registerReferenceProvider(
      XmlPatterns.xmlAttributeValue().withLocalName(or(string().endsWith(STYLE_NAME_ATTR_SUFFIX),
                                                       string().equalTo(STYLE_NAME_ATTR)))
        .and(new FilterPattern(new ElementFilter() {
          public boolean isAcceptable(final Object element, final PsiElement context) {
            return !((PsiElement)element).textContains('{');
          }

          public boolean isClassAcceptable(final Class hintClass) {
            return true;
          }
        })),
      CssReferenceProviderUtil.CSS_CLASS_OR_ID_KEY_PROVIDER.getProvider());

    XmlUtil.registerXmlAttributeValueReferenceProvider(registrar, null, new ElementFilter() {
      @Override
      public boolean isAcceptable(Object element, PsiElement context) {
        PsiElement parent = ((PsiElement)element).getParent();
        if (parent instanceof XmlAttribute) {
          XmlAttributeDescriptor descriptor = ((XmlAttribute)parent).getDescriptor();
          if (descriptor instanceof AnnotationBackedDescriptorImpl) {
            String format = ((AnnotationBackedDescriptor)descriptor).getFormat();
            return FlexCssPropertyDescriptor.COLOR_FORMAT.equals(format);
          }
        }
        return false;
      }

      @Override
      public boolean isClassAcceptable(Class hintClass) {
        return true;
      }
    }, true, new PsiReferenceProvider() {
      @NotNull
      @Override
      public PsiReference[] getReferencesByElement(@NotNull final PsiElement element, @NotNull ProcessingContext context) {
        XmlAttributeValue value = (XmlAttributeValue)element;
        XmlAttribute parent = (XmlAttribute)value.getParent();
        int length = value.getTextLength();
        if (length >= 2) {
          AnnotationBackedDescriptor descriptor = (AnnotationBackedDescriptor)parent.getDescriptor();
          assert descriptor != null;
          if (JSCommonTypeNames.ARRAY_CLASS_NAME.equals(descriptor.getType())) {
            // drop quotes
            String text = element.getText().substring(1, length - 1);
            final List<PsiReference> references = new ArrayList<PsiReference>();
            new ArrayAttributeValueProcessor() {
              @Override
              protected void processElement(int start, int end) {
                references.add(new FlexColorReference(element, new TextRange(start + 1, end + 1)));
              }
            }.process(text);
            return references.toArray(new PsiReference[references.size()]);
          }
          else {
            // inside quotes
            return new PsiReference[]{new FlexColorReference(element, new TextRange(1, length - 1))};
          }
        }
        return PsiReference.EMPTY_ARRAY;
      }
    });

    XmlUtil.registerXmlAttributeValueReferenceProvider(registrar, null, new ElementFilter() {
      public boolean isAcceptable(final Object element, final PsiElement context) {
        PsiElement parent = ((PsiElement)element).getParent();
        if (!(parent instanceof XmlAttribute) || !((XmlAttribute)parent).isNamespaceDeclaration()) {
          return false;
        }

        final PsiElement parentParent = parent.getParent();
        if (parentParent instanceof XmlTag && MxmlJSClass.isInsideTagThatAllowsAnyXmlContent((XmlTag)parentParent)) {
          return false;
        }

        return true;
      }

      public boolean isClassAcceptable(final Class hintClass) {
        return true;
      }
    }, true, new PsiReferenceProvider() {
      @NotNull
      public PsiReference[] getReferencesByElement(@NotNull PsiElement element, @NotNull ProcessingContext context) {
        final String trimmedText = StringUtil.unquoteString(element.getText());
        if (CodeContext.isPackageBackedNamespace(trimmedText)) {
          final JSReferenceSet referenceSet = new JSReferenceSet(element, trimmedText, 1, false, false) {
            @Override
            protected JSTextReference createTextReference(String s, int offset, boolean methodRef) {
              return new JSTextReference(this, s, offset, methodRef) {
                @Override
                protected ResolveResult[] doResolve(@NotNull PsiFile psiFile) {
                  if ("*".equals(getCanonicalText())) {
                    return new ResolveResult[]{new JSResolveResult(mySet.getElement())};
                  }
                  return super.doResolve(psiFile);
                }

                @Override
                protected MyResolveProcessor createResolveProcessor(String name, PsiElement place, ResultSink resultSink) {
                  return new MyResolveProcessor(name, place, resultSink) {
                    @Override
                    public boolean execute(@NotNull PsiElement element, @NotNull ResolveState state) {
                      if (!(element instanceof JSPackageWrapper)) return true;
                      return super.execute(element, state);
                    }
                  };
                }
              };
            }
          };
          return referenceSet.getReferences();
        }
        else {
          return PsiReference.EMPTY_ARRAY;
        }
      }
    });

    // source attribute of Binding tag is handled in JSLanguageInjector
    XmlUtil.registerXmlAttributeValueReferenceProvider(
      registrar,
      new String[]{FlexReferenceContributor.DESTINATION_ATTR_NAME},
      new ScopeFilter(new ParentElementFilter(new AndFilter(XmlTagFilter.INSTANCE, new TagNameFilter(BINDING_TAG_NAME),
                                                            new NamespaceFilter(JavaScriptSupportLoader.LANGUAGE_NAMESPACES)), 2)),
      new PsiReferenceProvider() {
        @NotNull
        public PsiReference[] getReferencesByElement(@NotNull final PsiElement element,
                                                     @NotNull final ProcessingContext context) {
          final String trimmedText = StringUtil.unquoteString(element.getText());
          final JSReferenceSet referenceSet =
            new JSReferenceSet(element, trimmedText, 1, false);
          return referenceSet.getReferences();
        }
      });

    XmlUtil.registerXmlAttributeValueReferenceProvider(registrar, new String[]{FlexReferenceContributor.SOURCE_ATTR_NAME}, new ScopeFilter(
      new ParentElementFilter(new AndFilter(XmlTagFilter.INSTANCE, new ElementFilterBase<PsiElement>(PsiElement.class) {
        protected boolean isElementAcceptable(final PsiElement element, final PsiElement context) {
          return true;
        }
      }), 2)), new PsiReferenceProvider() {
      @NotNull
      public PsiReference[] getReferencesByElement(@NotNull final PsiElement element, @NotNull final ProcessingContext context) {
        final XmlAttribute attribute = (XmlAttribute)element.getParent();
        final XmlTag tag = attribute.getParent();
        final String tagName = tag.getLocalName();

        final String trimmedText = StringUtil.unquoteString(element.getText());

        if (JavaScriptSupportLoader.isLanguageNamespace(tag.getNamespace())) {
          if (FlexPredefinedTagNames.SCRIPT.equals(tagName)) {
            return ReferenceSupport.getFileRefs(element, element, 1, ReferenceSupport.LookupOptions.SCRIPT_SOURCE);
          }

          final String[] tagsWithSourceAttr = {
            MxmlJSClass.XML_TAG_NAME, FlexPredefinedTagNames.MODEL,
            JSCommonTypeNames.STRING_CLASS_NAME, JSCommonTypeNames.BOOLEAN_CLASS_NAME,
            JSCommonTypeNames.INT_TYPE_NAME, JSCommonTypeNames.UINT_TYPE_NAME, JSCommonTypeNames.NUMBER_CLASS_NAME
          };

          if (ArrayUtil.contains(tagName, tagsWithSourceAttr)) {
            return ReferenceSupport.getFileRefs(element, element, 1, ReferenceSupport.LookupOptions.XML_AND_MODEL_SOURCE);
          }

          if (FlexPredefinedTagNames.STYLE.equals(tagName)) {
            if (trimmedText.startsWith("http:")) {
              return PsiReference.EMPTY_ARRAY;
            }
            else {
              return ReferenceSupport.getFileRefs(element, element, 1, ReferenceSupport.LookupOptions.STYLE_SOURCE);
            }
          }
        }

        if (element.textContains('{') || element.textContains('@')) {
          return PsiReference.EMPTY_ARRAY;
        }

        final XmlAttributeDescriptor descriptor = attribute.getDescriptor();
        final PsiElement psiElement = descriptor == null ? null : descriptor.getDeclaration();

        if (psiElement instanceof JSFunction) {
          final JSAttribute inspectableAttr = AnnotationBackedDescriptorImpl.findInspectableAttr(psiElement);
          if (inspectableAttr != null) {
            final JSAttributeNameValuePair attributeNameValuePair = inspectableAttr.getValueByName(FORMAT_ATTR_NAME);
            if (attributeNameValuePair != null && FILE_ATTR_VALUE.equals(attributeNameValuePair.getSimpleValue())) {
              return ReferenceSupport.getFileRefs(element, element, 1, ReferenceSupport.LookupOptions.NON_EMBEDDED_ASSET);
            }
          }
        }

        return PsiReference.EMPTY_ARRAY;
      }
    });

    final Function<PsiReference, LocalQuickFix[]> quickFixProvider = new Function<PsiReference, LocalQuickFix[]>() {
      @Nullable
      @Override
      public LocalQuickFix[] fun(PsiReference reference) {
        final PsiElement element = reference.getElement();

        final String classFqn = getTrimmedValueAndRange((XmlElement)element).first;
        final String tagOrAttrName = element instanceof XmlAttributeValue
                                     ? ((XmlAttribute)element.getParent()).getName()
                                     : ((XmlTag)element).getLocalName();


        final CreateClassIntentionWithCallback[] intentions;
        if (SKIN_CLASS_ATTR_NAME.equals(tagOrAttrName)) {
          intentions = new CreateClassIntentionWithCallback[]{new CreateFlexSkinIntention(classFqn, element)};
        }
        else if ("firstView".equals(tagOrAttrName)) {
          intentions = new CreateClassIntentionWithCallback[]{new CreateFlexMobileViewIntentionAndFix(classFqn, element, false)};
        }
        else {
          intentions = new CreateClassIntentionWithCallback[]{
            new CreateClassOrInterfaceFix(classFqn, null, element),
            new CreateFlexComponentFix(classFqn, element)
          };
        }

        for (CreateClassIntentionWithCallback intention : intentions) {
          intention.setCreatedClassFqnConsumer(new Consumer<String>() {
            @Override
            public void consume(final String fqn) {
              if (!element.isValid()) return;

              if (element instanceof XmlAttributeValue) {
                ((XmlAttribute)element.getParent()).setValue(fqn);
              }
              else {
                ((XmlTag)element).getValue().setText(fqn);
              }
            }
          });
        }
        return intentions;
      }
    };

    XmlUtil.registerXmlTagReferenceProvider(registrar, null, TrueFilter.INSTANCE, true,
                                            createReferenceProviderForTagOrAttributeExpectingJSClass(quickFixProvider));

    XmlUtil.registerXmlAttributeValueReferenceProvider(registrar, null, TrueFilter.INSTANCE,
                                                       createReferenceProviderForTagOrAttributeExpectingJSClass(quickFixProvider));

    registrar.registerReferenceProvider(xmlAttribute().withParent(XmlTag.class).with(new PatternCondition<XmlAttribute>("") {
      @Override
      public boolean accepts(@NotNull XmlAttribute xmlAttribute, ProcessingContext context) {
        String attrName = xmlAttribute.getLocalName();
        int dotPos = attrName.indexOf('.');
        if (dotPos == -1) return false;
        return JavaScriptSupportLoader.isFlexMxmFile(xmlAttribute.getContainingFile());
      }
    }), new PsiReferenceProvider() {
      @NotNull
      public PsiReference[] getReferencesByElement(@NotNull final PsiElement element, @NotNull final ProcessingContext context) {
        String attrName = ((XmlAttribute)element).getLocalName();
        int dotPos = attrName.indexOf('.');
        if (dotPos == -1) return PsiReference.EMPTY_ARRAY;
        return new PsiReference[]{new FlexReferenceContributor.StateReference(element, new TextRange(dotPos + 1, attrName.length()))};
      }
    });

    XmlUtil.registerXmlTagReferenceProvider(
      registrar, null, new ElementFilterBase<XmlTag>(XmlTag.class) {
        protected boolean isElementAcceptable(final XmlTag element, final PsiElement context) {
          return element.getName().indexOf('.') != -1;
        }
      }, false, new PsiReferenceProvider() {
        @NotNull
        public PsiReference[] getReferencesByElement(@NotNull final PsiElement element, @NotNull final ProcessingContext context) {
          final String name = ((XmlTag)element).getName();
          int dotIndex = name.indexOf('.');
          if (dotIndex == -1) return PsiReference.EMPTY_ARRAY;

          final int tagOffset = element.getTextRange().getStartOffset();
          final XmlToken startTagElement = XmlTagUtil.getStartTagNameElement((XmlTag)element);
          final XmlToken endTagElement = XmlTagUtil.getEndTagNameElement((XmlTag)element);
          if (startTagElement != null) {
            if (endTagElement != null && endTagElement.getText().equals(startTagElement.getText())) {
              final int start1 = startTagElement.getTextRange().getStartOffset() - tagOffset;
              final int start2 = endTagElement.getTextRange().getStartOffset() - tagOffset;
              return new PsiReference[]{
                new FlexReferenceContributor.StateReference(element,
                                                            new TextRange(start1 + dotIndex + 1,
                                                                          startTagElement.getTextRange().getEndOffset() - tagOffset)),
                new FlexReferenceContributor.StateReference(element,
                                                            new TextRange(start2 + dotIndex + 1,
                                                                          endTagElement.getTextRange().getEndOffset() - tagOffset)),
              };
            }
            else {
              final int start = startTagElement.getTextRange().getStartOffset() - tagOffset;
              return new PsiReference[]{
                new FlexReferenceContributor.StateReference(element,
                                                            new TextRange(start + dotIndex + 1,
                                                                          startTagElement.getTextRange().getEndOffset() - tagOffset))};
            }
          }

          return PsiReference.EMPTY_ARRAY;
        }
      }
    );


    XmlUtil.registerXmlAttributeValueReferenceProvider(
      registrar,
      new String[]{"basedOn", "fromState", "toState", FlexStateElementNames.NAME, FlexStateElementNames.STATE_GROUPS},
      new ScopeFilter(new ParentElementFilter(new AndFilter(XmlTagFilter.INSTANCE, new NamespaceFilter(MxmlJSClass.MXML_URIS)), 2)),
      new PsiReferenceProvider() {
        @NotNull
        public PsiReference[] getReferencesByElement(@NotNull final PsiElement element, @NotNull final ProcessingContext context) {
          final PsiElement parent = element.getParent();
          final PsiElement tag = parent.getParent();

          PsiReference ref = null;
          String tagName = ((XmlTag)tag).getLocalName();
          String attrName = ((XmlAttribute)parent).getName();
          String attrValue = ((XmlAttribute)parent).getValue();

          if (attrValue != null && attrValue.contains("{")) return PsiReference.EMPTY_ARRAY;

          if (FlexStateElementNames.NAME.equals(attrName)) {
            if ("State".equals(tagName)) {
              ref = new AttributeValueSelfReference(element);
            }
            else {
              return PsiReference.EMPTY_ARRAY;
            }
          }
          else if ("basedOn".equals(attrName) && element.getTextLength() == 2) {
            return PsiReference.EMPTY_ARRAY;
          }
          else if (FlexStateElementNames.STATE_GROUPS.equals(attrName)) {
            if ("State".equals(tagName)) {
              return buildStateRefs(element, true);
            }
            else {
              return PsiReference.EMPTY_ARRAY;
            }
          }

          if (FlexReferenceContributor.TRANSITION_TAG_NAME.equals(tagName)) {
            if ((element.textContains('*') &&
                 "*".equals(StringUtil.unquoteString(element.getText()))) ||
                element.getTextLength() == 2 // empty value for attr, current state
              ) {
              return PsiReference.EMPTY_ARRAY;
            }
          }

          if (ref == null) {
            ref = new FlexReferenceContributor.StateReference(element);
          }

          return new PsiReference[]{ref};
        }
      });

    XmlUtil.registerXmlAttributeValueReferenceProvider(
      registrar,
      new String[]{FlexStateElementNames.EXCLUDE_FROM, FlexStateElementNames.INCLUDE_IN},
      new ScopeFilter(new ParentElementFilter(XmlTagFilter.INSTANCE, 2)),
      new PsiReferenceProvider() {
        @NotNull
        public PsiReference[] getReferencesByElement(@NotNull final PsiElement element,
                                                     @NotNull final ProcessingContext context) {
          return buildStateRefs(element, false);
        }
      });

    XmlUtil.registerXmlAttributeValueReferenceProvider(
      registrar, new String[]{CodeContext.TARGET_ATTR_NAME},
      new ScopeFilter(
        new ParentElementFilter(
          new AndFilter(XmlTagFilter.INSTANCE,
                        new TagNameFilter(CodeContext.REPARENT_TAG_NAME),
                        new NamespaceFilter(JavaScriptSupportLoader.MXML_URI3)
          ), 2
        )
      ),
      new PsiReferenceProvider() {
        @NotNull
        public PsiReference[] getReferencesByElement(@NotNull final PsiElement element,
                                                     @NotNull final ProcessingContext context) {
          return new PsiReference[]{new XmlIdValueReference(element)};
        }
      });
  }

  private static PsiReferenceProvider createReferenceProviderForTagOrAttributeExpectingJSClass(final Function<PsiReference, LocalQuickFix[]> quickFixProvider) {
    return new PsiReferenceProvider() {
      @NotNull
      public PsiReference[] getReferencesByElement(@NotNull final PsiElement element,
                                                   @NotNull final ProcessingContext context) {
        final PsiMetaData descriptor;
        final String name;

        if (element instanceof XmlTag) {
          descriptor = ((XmlTag)element).getDescriptor();
          name = ((XmlTag)element).getLocalName();
        }
        else if (element instanceof XmlAttributeValue) {
          final XmlAttribute xmlAttribute = PsiTreeUtil.getParentOfType(element, XmlAttribute.class);
          descriptor = xmlAttribute == null ? null : xmlAttribute.getDescriptor();
          name = xmlAttribute == null ? "" : xmlAttribute.getName();
        }
        else {
          assert false : element;
          return PsiReference.EMPTY_ARRAY;
        }

        if (!(descriptor instanceof AnnotationBackedDescriptor)) return PsiReference.EMPTY_ARRAY;

        final String type = ((AnnotationBackedDescriptor)descriptor).getType();
        if (!FlexReferenceContributor.isClassReferenceType(type)) return PsiReference.EMPTY_ARRAY;

        final Pair<String, TextRange> trimmedValueAndRange = getTrimmedValueAndRange((XmlElement)element);
        if (trimmedValueAndRange.second.getStartOffset() == 0) return PsiReference.EMPTY_ARRAY;
        if (trimmedValueAndRange.first.indexOf('{') != -1 || trimmedValueAndRange.first.indexOf('@') != -1) return PsiReference.EMPTY_ARRAY;

        final JSReferenceSet jsReferenceSet =
          new JSReferenceSet(element, trimmedValueAndRange.first, trimmedValueAndRange.second.getStartOffset(), false, true) {
            @Override
            protected JSTextReference createTextReference(String s, int offset, boolean methodRef) {
              return new MyJSTextReference(this, s, offset, methodRef, quickFixProvider);
            }
          };
        if (SKIN_CLASS_ATTR_NAME.equals(name)) {
          jsReferenceSet.setBaseClassFqns(Collections.singletonList(UI_COMPONENT_FQN));
        }
        return jsReferenceSet.getReferences();
      }
    };
  }

  private static class MyJSTextReference extends JSTextReference implements LocalQuickFixProvider {
    private final Function<PsiReference, LocalQuickFix[]> myQuickFixProvider;

    MyJSTextReference(JSReferenceSet set,
                      String s,
                      int offset,
                      boolean methodRef,
                      Function<PsiReference, LocalQuickFix[]> quickFixProvider) {
      super(set, s, offset, methodRef);
      myQuickFixProvider = quickFixProvider;
    }

    @Override
    public LocalQuickFix[] getQuickFixes() {
      if (myQuickFixProvider != null) {
        return myQuickFixProvider.fun(this);
      }
      return super.getQuickFixes();
    }
  }

  private static Pair<String, TextRange> getTrimmedValueAndRange(final @NotNull XmlElement xmlElement) {
    if (xmlElement instanceof XmlTag) {
      return Pair.create(((XmlTag)xmlElement).getValue().getTrimmedText(), ElementManipulators.getValueTextRange(xmlElement));
    }
    else if (xmlElement instanceof XmlAttributeValue) {
      final String value = ((XmlAttributeValue)xmlElement).getValue();
      final String trimmedText = value.trim();
      final int index = xmlElement.getText().indexOf(trimmedText);
      return index < 0 || trimmedText.length() == 0
             ? Pair.create(value, ((XmlAttributeValue)xmlElement).getValueTextRange())
             : Pair.create(trimmedText, new TextRange(index, index + trimmedText.length()));
    }
    else {
      assert false;
      return Pair.create(null, null);
    }
  }

  private static PsiReference[] buildStateRefs(PsiElement element, boolean stateGroupsOnly) {
    SmartList<PsiReference> refs = new SmartList<PsiReference>();
    StringTokenizer t = new StringTokenizer(StringUtil.unquoteString(element.getText()), FlexReferenceContributor.DELIMS);

    while (t.hasMoreElements()) {
      String val = t.nextElement();
      int end = t.getCurrentPosition();
      refs.add(new FlexReferenceContributor.StateReference(element, new TextRange(1 + end - val.length(), 1 + end), stateGroupsOnly));
    }

    return refs.toArray(new PsiReference[refs.size()]);
  }
}