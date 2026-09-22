package com.smallzhuge.worktreesync.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 极简的 .idea 配置读取器。只认识三种结构：
 * <ul>
 *   <li>{@code <component name="X"><option name="Y" value="Z"/></component>} → {@link #readOption}</li>
 *   <li>{@code <component name="X"><parent><option .../></parent></component>} → {@link #readNestedOptionMap}</li>
 *   <li>{@code <component name="X"><item><option .../></item><item>...</item></component>} → {@link #readRepeatedOptionMaps}</li>
 * </ul>
 *
 * <p>刻意不引入任何第三方 XML 库 —— 本插件的目的是修复别人的环境，
 * 自身依赖越少越不容易出问题。
 */
public final class XmlOptions {

    private XmlOptions() {
    }

    public static String readOption(Path xmlFile, String componentName, String optionName) {
        Document doc = parse(xmlFile);
        if (doc == null) {
            return null;
        }
        Element component = findComponent(doc, componentName);
        if (component == null) {
            return null;
        }
        NodeList options = component.getElementsByTagName("option");
        for (int i = 0; i < options.getLength(); i++) {
            Element option = (Element) options.item(i);
            if (optionName.equals(option.getAttribute("name"))) {
                String value = option.getAttribute("value");
                return value == null || value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    public static Integer readIntOption(Path xmlFile, String componentName, String optionName) {
        String raw = readOption(xmlFile, componentName, optionName);
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 读取 {@code encodings.xml} 里 PROJECT 级的 charset：
     * {@code <component name="Encoding"><file url="PROJECT" charset="UTF-8"/></component>}
     */
    public static String readProjectCharset(Path encodingsXml) {
        Document doc = parse(encodingsXml);
        if (doc == null) {
            return null;
        }
        NodeList files = doc.getElementsByTagName("file");
        for (int i = 0; i < files.getLength(); i++) {
            Element file = (Element) files.item(i);
            if ("PROJECT".equals(file.getAttribute("url"))) {
                String charset = file.getAttribute("charset");
                return charset == null || charset.isEmpty() ? null : charset;
            }
        }
        return null;
    }

    /**
     * 读取「某个子元素」下的 option 表（只取直接子节点，不递归）。
     *
     * <p>用途：Maven 设置存在 {@code workspace.xml} 的嵌套结构里，普通 readOption 取不到：
     * <pre>
     * &lt;component name="MavenImportPreferences"&gt;
     *   &lt;option name="generalSettings"&gt;
     *     &lt;MavenGeneralSettings&gt;
     *       &lt;option name="customMavenHome" value="E:\maven" /&gt;
     *       &lt;option name="userSettingsFile" value="E:\maven\conf\settings.xml" /&gt;
     * </pre>
     */
    public static Map<String, String> readNestedOptionMap(Path xmlFile, String componentName,
                                                          String parentElementName) {
        Map<String, String> result = new LinkedHashMap<>();
        Document doc = parse(xmlFile);
        if (doc == null) {
            return result;
        }
        Element component = findComponent(doc, componentName);
        if (component == null) {
            return result;
        }
        NodeList parents = component.getElementsByTagName(parentElementName);
        if (parents.getLength() == 0) {
            return result;
        }
        collectChildOptions((Element) parents.item(0), result);
        return result;
    }

    /**
     * 读取重复出现的子元素，每个元素收成一张 option 表。
     *
     * <p>用途：{@code jarRepositories.xml} 里一连串
     * {@code <remote-repository><option name="id"/><option name="url"/></remote-repository>}。
     */
    public static List<Map<String, String>> readRepeatedOptionMaps(Path xmlFile, String componentName,
                                                                  String elementName) {
        List<Map<String, String>> result = new ArrayList<>();
        Document doc = parse(xmlFile);
        if (doc == null) {
            return result;
        }
        Element component = findComponent(doc, componentName);
        if (component == null) {
            return result;
        }
        NodeList items = component.getElementsByTagName(elementName);
        for (int i = 0; i < items.getLength(); i++) {
            Map<String, String> options = new LinkedHashMap<>();
            collectChildOptions((Element) items.item(i), options);
            if (!options.isEmpty()) {
                result.add(options);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ 内部

    private static void collectChildOptions(Element parent, Map<String, String> target) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element element = (Element) node;
            if (!"option".equals(element.getTagName())) {
                continue;
            }
            String name = element.getAttribute("name");
            if (name == null || name.isEmpty()) {
                continue;
            }
            target.put(name, element.getAttribute("value"));
        }
    }

    private static Element findComponent(Document doc, String componentName) {
        NodeList components = doc.getElementsByTagName("component");
        for (int i = 0; i < components.getLength(); i++) {
            Element component = (Element) components.item(i);
            if (componentName.equals(component.getAttribute("name"))) {
                return component;
            }
        }
        return null;
    }

    private static Document parse(Path xmlFile) {
        if (xmlFile == null || !Files.isRegularFile(xmlFile)) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            safe(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            safe(factory, "http://xml.org/sax/features/external-general-entities", false);
            safe(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            try {
                factory.setXIncludeAware(false);
                factory.setExpandEntityReferences(false);
            } catch (UnsupportedOperationException ignored) {
                // 某些解析器实现不支持，忽略
            }
            return factory.newDocumentBuilder().parse(xmlFile.toFile());
        } catch (Exception ignored) {
            // 配置损坏 / 不存在都视为「没有这个设置」，不打断流程
            return null;
        }
    }

    private static void safe(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception ignored) {
        }
    }
}
