/*
 * Copyright (c) 2005 Your Corporation. All Rights Reserved.
 */
package com.intellij.psi.impl.search;

import com.intellij.lang.properties.psi.PropertiesFile;
import com.intellij.lang.properties.psi.Property;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.PsiReferenceSearch;
import com.intellij.util.Processor;
import com.intellij.util.QueryExecutor;

/**
 * @author max
 */
public class FormReferencesSearcher implements QueryExecutor<PsiReference, PsiReferenceSearch.SearchParameters> {
  public boolean execute(final PsiReferenceSearch.SearchParameters p, final Processor<PsiReference> consumer) {
    final PsiElement refElement = p.getElementToSearch();
    final SearchScope scope = p.getScope();
    if (refElement instanceof PsiPackage && scope instanceof GlobalSearchScope) {
      if (!UIFormUtil.processReferencesInUIForms(consumer, (PsiPackage)refElement, (GlobalSearchScope)scope)) return false;
    }
    else if (refElement instanceof PsiClass && scope instanceof GlobalSearchScope) {
      if (!UIFormUtil.processReferencesInUIForms(consumer, (PsiClass)refElement, (GlobalSearchScope)scope)) return false;
    }
    else if (refElement instanceof PsiField && scope instanceof GlobalSearchScope) {
      if (!UIFormUtil.processReferencesInUIForms(consumer, (PsiField)refElement, (GlobalSearchScope)scope)) return false;
    }
    else if (refElement instanceof Property && scope instanceof GlobalSearchScope) {
      if (!UIFormUtil.processReferencesInUIForms(consumer, (Property)refElement, (GlobalSearchScope)scope)) return false;
    }
    else if (refElement instanceof PropertiesFile && scope instanceof GlobalSearchScope) {
      if (!UIFormUtil.processReferencesInUIForms(consumer, (PropertiesFile)refElement, (GlobalSearchScope)scope)) return false;
    }

    return true;
  }
}
