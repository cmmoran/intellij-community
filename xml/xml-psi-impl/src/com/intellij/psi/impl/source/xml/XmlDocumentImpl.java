/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.intellij.psi.impl.source.xml;

import com.intellij.javaee.ExternalResourceManager;
import com.intellij.javaee.ExternalResourceManagerEx;
import com.intellij.lang.ASTNode;
import com.intellij.lang.dtd.DTDLanguage;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.PomManager;
import com.intellij.pom.PomModel;
import com.intellij.pom.event.PomModelEvent;
import com.intellij.pom.impl.PomTransactionBase;
import com.intellij.pom.xml.XmlAspect;
import com.intellij.pom.xml.impl.events.XmlDocumentChangedImpl;
import com.intellij.psi.*;
import com.intellij.psi.impl.PsiCachedValueImpl;
import com.intellij.psi.impl.meta.MetaRegistry;
import com.intellij.psi.impl.source.html.dtd.HtmlNSDescriptorImpl;
import com.intellij.psi.impl.source.tree.CompositePsiElement;
import com.intellij.psi.impl.source.tree.TreeElement;
import com.intellij.psi.meta.PsiMetaData;
import com.intellij.psi.meta.PsiMetaOwner;
import com.intellij.psi.tree.ChildRoleBase;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.xml.*;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.concurrency.AtomicFieldUpdater;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.xml.Html5SchemaProvider;
import com.intellij.xml.XmlExtension;
import com.intellij.xml.XmlNSDescriptor;
import com.intellij.xml.index.XmlNamespaceIndex;
import com.intellij.xml.util.XmlNSDescriptorSequence;
import com.intellij.xml.util.XmlUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

/**
 * @author Mike
 */
public class XmlDocumentImpl extends XmlElementImpl implements XmlDocument {

  private static final Key<Boolean> AUTO_GENERATED = Key.create("auto-generated xml schema");

  public static boolean isAutoGeneratedSchema(XmlFile file) {
    return file.getUserData(AUTO_GENERATED) != null;
  }

  private static final Logger LOG = Logger.getInstance("#com.intellij.psi.impl.source.xml.XmlDocumentImpl");
  private static final AtomicFieldUpdater<XmlDocumentImpl, XmlProlog>
    MY_PROLOG_UPDATER = AtomicFieldUpdater.forFieldOfType(XmlDocumentImpl.class, XmlProlog.class);
  private static final AtomicFieldUpdater<XmlDocumentImpl, XmlTag>
    MY_ROOT_TAG_UPDATER = AtomicFieldUpdater.forFieldOfType(XmlDocumentImpl.class, XmlTag.class);

  private volatile XmlProlog myProlog;
  private volatile XmlTag myRootTag;
  private volatile long myExtResourcesModCount = -1;

  public XmlDocumentImpl() {
    this(XmlElementType.XML_DOCUMENT);
  }

  protected XmlDocumentImpl(IElementType type) {
    super(type);
  }

  @Override
  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof XmlElementVisitor) {
      ((XmlElementVisitor)visitor).visitXmlDocument(this);
    }
    else {
      visitor.visitElement(this);
    }
  }

  @Override
  public int getChildRole(ASTNode child) {
    LOG.assertTrue(child.getTreeParent() == this);
    IElementType i = child.getElementType();
    if (i == XmlElementType.XML_PROLOG) {
      return XmlChildRole.XML_PROLOG;
    }
    else if (i == XmlElementType.XML_TAG) {
      return XmlChildRole.XML_TAG;
    }
    else {
      return ChildRoleBase.NONE;
    }
  }

  @Override
  public XmlProlog getProlog() {
    XmlProlog prolog = myProlog;

    if (prolog == null) {
      prolog = (XmlProlog)findElementByTokenType(XmlElementType.XML_PROLOG);

      if(!MY_PROLOG_UPDATER.compareAndSet(this, null, prolog)) {
        prolog = MY_PROLOG_UPDATER.get(this);
      }
    }

    return prolog;
  }

  @Override
  public XmlTag getRootTag() {
    XmlTag rootTag = myRootTag;

    if (rootTag == null) {
      rootTag = (XmlTag)findElementByTokenType(XmlElementType.XML_TAG);

      if (!MY_ROOT_TAG_UPDATER.compareAndSet(this, null, rootTag)) {
        rootTag = MY_ROOT_TAG_UPDATER.get(this);
      }
    }

    return rootTag;
  }

  @Override
  @SuppressWarnings("ConstantConditions")
  public XmlNSDescriptor getRootTagNSDescriptor() {
    XmlTag rootTag = getRootTag();
    return rootTag != null ? rootTag.getNSDescriptor(rootTag.getNamespace(), false) : null;
  }

  private ConcurrentMap<String, CachedValue<XmlNSDescriptor>> myDefaultDescriptorsCacheStrict =
    ContainerUtil.newConcurrentMap();
  private ConcurrentMap<String, CachedValue<XmlNSDescriptor>> myDefaultDescriptorsCacheNotStrict =
    ContainerUtil.newConcurrentMap();

  @Override
  public void clearCaches() {
    myDefaultDescriptorsCacheStrict.clear();
    myDefaultDescriptorsCacheNotStrict.clear();
    myRootTag = null;
    myProlog = null;
    super.clearCaches();
  }

  @Nullable
  @Override
  public XmlNSDescriptor getDefaultNSDescriptor(final String namespace, final boolean strict) {
    long curExtResourcesModCount = ExternalResourceManagerEx.getInstanceEx().getModificationCount(getProject());
    if (myExtResourcesModCount != curExtResourcesModCount) {
      myDefaultDescriptorsCacheNotStrict.clear();
      myDefaultDescriptorsCacheStrict.clear();
      myExtResourcesModCount = curExtResourcesModCount;
    }

    final ConcurrentMap<String, CachedValue<XmlNSDescriptor>> defaultDescriptorsCache;
    if (strict) {
      defaultDescriptorsCache = myDefaultDescriptorsCacheStrict;
    }
    else {
      defaultDescriptorsCache = myDefaultDescriptorsCacheNotStrict;
    }

    CachedValue<XmlNSDescriptor> cachedValue = defaultDescriptorsCache.get(namespace);
    if (cachedValue == null) {
      defaultDescriptorsCache.put(namespace, cachedValue = new PsiCachedValueImpl<>(getManager(), () -> {
        final XmlNSDescriptor defaultNSDescriptorInner = getDefaultNSDescriptorInner(namespace, strict);

        if (isGeneratedFromDtd(defaultNSDescriptorInner)) {
          return new CachedValueProvider.Result<>(defaultNSDescriptorInner, this, ExternalResourceManager.getInstance());
        }

        return new CachedValueProvider.Result<>(defaultNSDescriptorInner, defaultNSDescriptorInner != null
                                                                          ? defaultNSDescriptorInner.getDependences()
                                                                          : ExternalResourceManager.getInstance());
      }));
    }
    return cachedValue.getValue();
  }

  private boolean isGeneratedFromDtd(XmlNSDescriptor defaultNSDescriptorInner) {
    if (defaultNSDescriptorInner == null) {
      return false;
    }
    XmlFile descriptorFile = defaultNSDescriptorInner.getDescriptorFile();
    if (descriptorFile == null) {
        return false;
    }
    @NonNls String otherName = XmlUtil.getContainingFile(this).getName() + ".dtd";
    return descriptorFile.getName().equals(otherName);
  }

  private XmlNSDescriptor getDefaultNSDescriptorInner(final String namespace, final boolean strict) {
    final XmlFile containingFile = XmlUtil.getContainingFile(this);
    if (containingFile == null) return null;
    final XmlProlog prolog = getProlog();
    final XmlDoctype doctype = prolog != null ? prolog.getDoctype() : null;
    boolean dtdUriFromDocTypeIsNamespace = false;

    if (XmlUtil.HTML_URI.equals(namespace)) {
      XmlNSDescriptor nsDescriptor = doctype != null ? getNsDescriptorFormDocType(doctype, containingFile, true) : null;
      if (doctype != null) {
        LOG.debug(
          "Descriptor from doctype " + doctype + " is " + (nsDescriptor != null ? nsDescriptor.getClass().getCanonicalName() : "NULL"));
      }

      if (nsDescriptor == null) {
        String htmlns = ExternalResourceManagerEx.getInstanceEx().getDefaultHtmlDoctype(getProject());
        if (htmlns.isEmpty()) {
          htmlns = Html5SchemaProvider.getHtml5SchemaLocation();
        }
        nsDescriptor = getDefaultNSDescriptor(htmlns, false);
      }
      if (nsDescriptor != null) {
        final XmlFile descriptorFile = nsDescriptor.getDescriptorFile();
        if (descriptorFile != null) {
          return getCachedHtmlNsDescriptor(descriptorFile);
        }
      }
      return new HtmlNSDescriptorImpl(nsDescriptor);
    }
    else if (XmlUtil.XHTML_URI.equals(namespace)) {
      String xhtmlNamespace = XmlUtil.getDefaultXhtmlNamespace(getProject());
      if (xhtmlNamespace == null || xhtmlNamespace.isEmpty()) {
        xhtmlNamespace = Html5SchemaProvider.getXhtml5SchemaLocation();
      }
      return getDefaultNSDescriptor(xhtmlNamespace, false);
    }
    else if (namespace != null && namespace != XmlUtil.EMPTY_URI) {
      if (doctype == null || !namespace.equals(XmlUtil.getDtdUri(doctype))) {
        boolean documentIsSchemaThatDefinesNs = namespace.equals(XmlUtil.getTargetSchemaNsFromTag(getRootTag()));

        final XmlFile xmlFile = documentIsSchemaThatDefinesNs
                                ? containingFile
                                : XmlUtil.findNamespace(containingFile, namespace);
        if (xmlFile != null) {
          final XmlDocument document = xmlFile.getDocument();
          if (document != null) {
            return (XmlNSDescriptor)document.getMetaData();
          }
        }
      } else {
        dtdUriFromDocTypeIsNamespace = true;
      }
    }

    if (strict && !dtdUriFromDocTypeIsNamespace) return null;

    if (doctype != null) {
      XmlNSDescriptor descr = getNsDescriptorFormDocType(doctype, containingFile, false);

      if (descr != null) {
        return XmlExtension.getExtension(containingFile).getDescriptorFromDoctype(containingFile, descr);
      }
    }

    if (strict) return null;
    if (namespace == XmlUtil.EMPTY_URI) {
      final XmlFile xmlFile = XmlUtil.findNamespace(containingFile, namespace);
      if (xmlFile != null) {
        return (XmlNSDescriptor)xmlFile.getDocument().getMetaData();
      }
    }
    try {
      final PsiFile fileFromText = PsiFileFactory.getInstance(getProject())
        .createFileFromText(containingFile.getName() + ".dtd", DTDLanguage.INSTANCE, XmlUtil.generateDocumentDTD(this, false), false, false);
      if (fileFromText instanceof XmlFile) {
        fileFromText.putUserData(AUTO_GENERATED, Boolean.TRUE);
        return (XmlNSDescriptor)((XmlFile)fileFromText).getDocument().getMetaData();
      }
    }
    catch (ProcessCanceledException ex) {
      throw ex;
    }
    catch (RuntimeException ignored) {
    } // e.g. dtd isn't mapped to xml type

    return null;
  }

  private static XmlNSDescriptor getCachedHtmlNsDescriptor(final XmlFile descriptorFile) {
    return CachedValuesManager.getCachedValue(descriptorFile, () -> {
      final XmlDocument document = descriptorFile.getDocument();
      if (document == null) return CachedValueProvider.Result.create(null, descriptorFile);
      return CachedValueProvider.Result.<XmlNSDescriptor>create(new HtmlNSDescriptorImpl((XmlNSDescriptor)document.getMetaData()), descriptorFile);
    });
  }

  @NotNull
  private static String getFilePathForLogging(@Nullable PsiFile file) {
    if (file == null) {
      return "NULL";
    }
    final VirtualFile vFile = file.getVirtualFile();
    return vFile != null ? vFile.getPath() : "NULL_VFILE";
  }

  @Nullable
  private XmlNSDescriptor getNsDescriptorFormDocType(final XmlDoctype doctype, final XmlFile containingFile, final boolean forHtml) {
    XmlNSDescriptor descriptor = getNSDescriptorFromMetaData(doctype.getMarkupDecl(), true);

    final String filePath = getFilePathForLogging(containingFile);

    final String dtdUri = XmlUtil.getDtdUri(doctype);
    LOG.debug("DTD url for doctype " + doctype.getText() + " in file " + filePath + " is " + dtdUri);
    
    if (dtdUri != null && !dtdUri.isEmpty()){
      XmlFile xmlFile = XmlUtil.findNamespace(containingFile, dtdUri);
      if (xmlFile == null) {
        // try to auto-detect it
        xmlFile = XmlNamespaceIndex.guessDtd(dtdUri, containingFile);
      }
      final String schemaFilePath = getFilePathForLogging(xmlFile);
      
      LOG.debug("Schema file for " + filePath + " is " + schemaFilePath);
      
      XmlNSDescriptor descriptorFromDtd = getNSDescriptorFromMetaData(xmlFile == null ? null : xmlFile.getDocument(), forHtml);

      LOG.debug("Descriptor from meta data for schema file " +
                schemaFilePath +
                " is " +
                (descriptorFromDtd != null ? descriptorFromDtd.getClass().getCanonicalName() : "NULL"));

      if (descriptor != null && descriptorFromDtd != null){
        descriptor = new XmlNSDescriptorSequence(new XmlNSDescriptor[]{descriptor, descriptorFromDtd});
      }
      else if (descriptorFromDtd != null) {
        descriptor = descriptorFromDtd;
      }
    }
    return descriptor;
  }

  @Nullable
  private XmlNSDescriptor getNSDescriptorFromMetaData(@Nullable PsiMetaOwner metaOwner, boolean nonEmpty) {
    if (metaOwner == null) return null;
    XmlNSDescriptor descriptor = (XmlNSDescriptor)metaOwner.getMetaData();
    if (descriptor == null) return null;
    if (nonEmpty && descriptor.getRootElementsDescriptors(this).length == 0) {
      return null;
    }
    return descriptor;
  }

  @NotNull
  @Override
  public CompositePsiElement clone() {
    HashMap<String, CachedValue<XmlNSDescriptor>> cacheStrict = new HashMap<>(
      myDefaultDescriptorsCacheStrict
    );
    HashMap<String, CachedValue<XmlNSDescriptor>> cacheNotStrict = new HashMap<>(
      myDefaultDescriptorsCacheNotStrict
    );
    final XmlDocumentImpl copy = (XmlDocumentImpl) super.clone();
    updateSelfDependentDtdDescriptors(copy, cacheStrict, cacheNotStrict);
    return copy;
  }

  @Override
  public PsiElement copy() {
    HashMap<String, CachedValue<XmlNSDescriptor>> cacheStrict = new HashMap<>(
      myDefaultDescriptorsCacheStrict
    );
    HashMap<String, CachedValue<XmlNSDescriptor>> cacheNotStrict = new HashMap<>(
      myDefaultDescriptorsCacheNotStrict
    );
    final XmlDocumentImpl copy = (XmlDocumentImpl)super.copy();
    updateSelfDependentDtdDescriptors(copy, cacheStrict, cacheNotStrict);
    return copy;
  }

  private void updateSelfDependentDtdDescriptors(XmlDocumentImpl copy, HashMap<String,
    CachedValue<XmlNSDescriptor>> cacheStrict, HashMap<String, CachedValue<XmlNSDescriptor>> cacheNotStrict) {
    copy.myDefaultDescriptorsCacheNotStrict = ContainerUtil.newConcurrentMap();
    copy.myDefaultDescriptorsCacheStrict = ContainerUtil.newConcurrentMap();

    for(Map.Entry<String, CachedValue<XmlNSDescriptor>> e:cacheStrict.entrySet()) {
      if (e.getValue().hasUpToDateValue()) {
        final XmlNSDescriptor nsDescriptor = e.getValue().getValue();
        if (!isGeneratedFromDtd(nsDescriptor)) copy.myDefaultDescriptorsCacheStrict.put(e.getKey(), e.getValue());
      }
    }

    for(Map.Entry<String, CachedValue<XmlNSDescriptor>> e:cacheNotStrict.entrySet()) {
      if (e.getValue().hasUpToDateValue()) {
        final XmlNSDescriptor nsDescriptor = e.getValue().getValue();
        if (!isGeneratedFromDtd(nsDescriptor)) copy.myDefaultDescriptorsCacheNotStrict.put(e.getKey(), e.getValue());
      }
    }
  }

  @Override
  public PsiMetaData getMetaData() {
    return MetaRegistry.getMeta(this);
  }

  @Override
  public TreeElement addInternal(final TreeElement first, final ASTNode last, final ASTNode anchor, final Boolean before) {
    final PomModel model = PomManager.getModel(getProject());
    final XmlAspect aspect = model.getModelAspect(XmlAspect.class);
    final TreeElement[] holder = new TreeElement[1];
    try{
      model.runTransaction(new PomTransactionBase(this, aspect) {
        @Override
        public PomModelEvent runInner() {
          holder[0] = XmlDocumentImpl.super.addInternal(first, last, anchor, before);
          return XmlDocumentChangedImpl.createXmlDocumentChanged(model, XmlDocumentImpl.this);
        }
      });
    }
    catch(IncorrectOperationException ignored){}
    return holder[0];
  }

  @Override
  public void deleteChildInternal(@NotNull final ASTNode child) {
    final PomModel model = PomManager.getModel(getProject());
    final XmlAspect aspect = model.getModelAspect(XmlAspect.class);
    try{
      model.runTransaction(new PomTransactionBase(this, aspect) {
        @Override
        public PomModelEvent runInner() {
          XmlDocumentImpl.super.deleteChildInternal(child);
          return XmlDocumentChangedImpl.createXmlDocumentChanged(model, XmlDocumentImpl.this);
        }
      });
    }
    catch(IncorrectOperationException ignored){}
  }

  @Override
  public void replaceChildInternal(@NotNull final ASTNode child, @NotNull final TreeElement newElement) {
    final PomModel model = PomManager.getModel(getProject());
    final XmlAspect aspect = model.getModelAspect(XmlAspect.class);
    try{
      model.runTransaction(new PomTransactionBase(this, aspect) {
        @Override
        public PomModelEvent runInner() {
          XmlDocumentImpl.super.replaceChildInternal(child, newElement);
          return XmlDocumentChangedImpl.createXmlDocumentChanged(model, XmlDocumentImpl.this);
        }
      });
    }
    catch(IncorrectOperationException ignored){}
  }
}
