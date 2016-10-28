/*
    Copyright 1996-2008 Ariba, Inc.

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at
        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

    $Id:$
*/
package ariba.ideplugin.idea.lang.completion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.html.HtmlTag;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlDocument;
import com.intellij.psi.xml.XmlElement;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.ArrayUtil;
import com.intellij.util.NullableFunction;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.xml.XmlElementDescriptor;
import com.intellij.xml.XmlElementDescriptorAwareAboutChildren;
import com.intellij.xml.XmlExtension;
import com.intellij.xml.XmlNSDescriptor;
import com.intellij.xml.impl.schema.AnyXmlElementDescriptor;
import com.intellij.xml.impl.schema.XmlElementDescriptorImpl;
import com.intellij.xml.util.HtmlUtil;
import com.intellij.xml.util.XmlUtil;

/**
 * Taken from IDEA code since in the context of plugin this does not work well.
 * Even this code is 1:1 to IDEA code with not change and its also static it returns
 * bad values.
 */
public class TagNameReferenceExt
{

    public static String[] getTagNameVariants (final XmlTag element,
                                               final Collection<String> namespaces,
                                               @Nullable List<String> nsInfo)
    {

        XmlElementDescriptor elementDescriptor = null;
        String elementNamespace = null;

        final Map<String, XmlElementDescriptor> descriptorsMap = new HashMap<String,
                XmlElementDescriptor>();
        PsiElement context = element.getParent();
        PsiElement curElement = element.getParent();

        {
            while (curElement instanceof XmlTag) {
                final XmlTag declarationTag = (XmlTag)curElement;
                final String namespace = declarationTag.getNamespace();

                if (!descriptorsMap.containsKey(namespace)) {
                    final XmlElementDescriptor descriptor = declarationTag
                            .getDescriptor();

                    if (descriptor != null) {
                        descriptorsMap.put(namespace, descriptor);
                        if (elementDescriptor == null) {
                            elementDescriptor = descriptor;
                            elementNamespace = namespace;
                        }
                    }
                }
                curElement = curElement.getContext();
            }
        }

        final Set<XmlNSDescriptor> visited = new HashSet<XmlNSDescriptor>();
        final XmlExtension extension = XmlExtension.getExtension(element
                .getContainingFile());
        final ArrayList<XmlElementDescriptor> variants = new
                ArrayList<XmlElementDescriptor>();
        for (final String namespace : namespaces) {
            final int initialSize = variants.size();
            processVariantsInNamespace(namespace, element, variants, elementDescriptor,
                    elementNamespace, descriptorsMap, visited,
                    context instanceof XmlTag ? (XmlTag)context: element, extension);
            if (nsInfo != null) {
                for (int i = initialSize; i < variants.size(); i++) {
                    nsInfo.add(namespace);
                }
            }
        }

        final boolean hasPrefix = StringUtil.isNotEmpty(element.getNamespacePrefix());
        final List<String> list = ContainerUtil.mapNotNull(variants,
                new NullableFunction<XmlElementDescriptor, String>()
                {
                    public String fun (XmlElementDescriptor descriptor)
                    {
                        if (descriptor instanceof AnyXmlElementDescriptor) {
                            return null;
                        }
                        else if (hasPrefix && descriptor instanceof
                                XmlElementDescriptorImpl &&
                                !namespaces.contains(((XmlElementDescriptorImpl)
                                        descriptor).getNamespace())) {
                            return null;
                        }

                        return descriptor.getName(element);
                    }
                });
        return ArrayUtil.toStringArray(list);
    }

    private static void processVariantsInNamespace (final String namespace,
                                                    final XmlTag element,
                                                    final List<XmlElementDescriptor>
                                                            variants,
                                                    final XmlElementDescriptor
                                                            elementDescriptor,
                                                    final String elementNamespace,
                                                    final Map<String,
                                                            XmlElementDescriptor>
                                                            descriptorsMap,
                                                    final Set<XmlNSDescriptor> visited,
                                                    XmlTag parent,
                                                    final XmlExtension extension)
    {
        if (descriptorsMap.containsKey(namespace)) {
            final XmlElementDescriptor descriptor = descriptorsMap.get(namespace);

            if (isAcceptableNs(element, elementDescriptor, elementNamespace, namespace)) {
                for (XmlElementDescriptor containedDescriptor : descriptor
                        .getElementsDescriptors(parent)) {
                    if (containedDescriptor != null) {
                        variants.add(containedDescriptor);
                    }
                }
            }

            if (element instanceof HtmlTag) {
                HtmlUtil.addHtmlSpecificCompletions(descriptor, element, variants);
            }
            visited.add(descriptor.getNSDescriptor());
        }
        else {
            // Don't use default namespace in case there are other namespaces in scope
            // If there are tags from default namespace they will be handeled via
            // their element descriptors (prev if section)
            if (namespace == null) {
                return;
            }
            if (namespace.length() == 0 && !visited.isEmpty()) {
                return;
            }

            XmlNSDescriptor nsDescriptor = getDescriptor(element, namespace, true,
                    extension);
            if (nsDescriptor == null) {
                if (!descriptorsMap.isEmpty()) {
                    return;
                }
                nsDescriptor = getDescriptor(element, namespace, false, extension);
            }

            if (nsDescriptor != null && !visited.contains(nsDescriptor) &&
                    isAcceptableNs(element, elementDescriptor, elementNamespace,
                            namespace)
                    ) {
                visited.add(nsDescriptor);
                final XmlElementDescriptor[] rootElementsDescriptors =
                        nsDescriptor.getRootElementsDescriptors(PsiTreeUtil
                                .getParentOfType
                                        (element, XmlDocument.class));

                final XmlTag parentTag = extension.getParentTagForNamespace(element,
                        nsDescriptor);
                final XmlElementDescriptor parentDescriptor;
                if (parentTag == element.getParentTag()) {
                    parentDescriptor = elementDescriptor;
                }
                else {
                    assert parentTag != null;
                    parentDescriptor = parentTag.getDescriptor();
                }

                for (XmlElementDescriptor candidateDescriptor : rootElementsDescriptors) {
                    if (candidateDescriptor != null &&
                            couldContainDescriptor(parentTag, parentDescriptor,
                                    candidateDescriptor, namespace, false)) {
                        variants.add(candidateDescriptor);
                    }
                }
            }
        }
    }

    private static XmlNSDescriptor getDescriptor (final XmlTag element,
                                                  final String namespace,
                                                  final boolean strict,
                                                  final XmlExtension extension)
    {
        return extension.getNSDescriptor(element, namespace, strict);
    }

    private static boolean couldContainDescriptor (final XmlTag parentTag,
                                                   final XmlElementDescriptor
                                                           parentDescriptor,
                                                   final XmlElementDescriptor
                                                           childDescriptor,
                                                   String childNamespace, boolean strict)
    {

        if (XmlUtil.nsFromTemplateFramework(childNamespace)) {
            return true;
        }
        if (parentTag == null) {
            return true;
        }
        if (parentDescriptor == null) {
            return false;
        }
        final XmlTag childTag = parentTag.createChildTag(childDescriptor.getName(),
                childNamespace, null, false);
        childTag.putUserData(XmlElement.INCLUDING_ELEMENT, parentTag);
        XmlElementDescriptor descriptor = parentDescriptor.getElementDescriptor(childTag,
                parentTag);
        return descriptor != null && (!strict || !(descriptor instanceof
                AnyXmlElementDescriptor));
    }

    private static boolean isAcceptableNs (final XmlTag element,
                                           final XmlElementDescriptor elementDescriptor,
                                           final String elementNamespace,
                                           final String namespace)
    {
        return !(elementDescriptor instanceof XmlElementDescriptorAwareAboutChildren) ||
                elementNamespace == null ||
                elementNamespace.equals(namespace) ||
                ((XmlElementDescriptorAwareAboutChildren)elementDescriptor)
                        .allowElementsFromNamespace(namespace, element.getParentTag());
    }
}