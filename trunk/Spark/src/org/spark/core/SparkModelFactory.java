package org.spark.core;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;

import javax.xml.parsers.DocumentBuilderFactory;

import org.spark.math.RationalNumber;
import org.spark.runtime.internal.ModelMethod;
import org.spark.runtime.internal.ModelVariable;
import org.spark.utils.XmlDocUtils;

import org.w3c.dom.Document;
import org.w3c.dom.Node;

import com.spinn3r.log5j.Logger;

/**
 * Loads a model from an xml file
 * 
 * @author Monad
 * 
 */
public class SparkModelFactory {
	private static final Logger logger = Logger.getLogger();

	/**
	 * Loads a model from the given xml description file and using the path to
	 * this file as a root path
	 * 
	 * @param xmlFile
	 * @param rootPath
	 * @return
	 */
	public static SparkModel loadModel(File xmlFile) throws Exception {
		return loadModel(xmlFile, xmlFile.getParentFile());
	}

	/**
	 * Loads a model from the given xml description file and using the given
	 * root path to the model Java class files
	 * 
	 * @param xmlFile
	 * @param rootPath
	 * @return
	 */
	public static SparkModel loadModel(File xmlFile, File rootPath)
			throws Exception {
		Document doc = DocumentBuilderFactory.newInstance()
				.newDocumentBuilder().parse(xmlFile);
		return loadModel(doc, rootPath);
	}

	/**
	 * Loads a model from the given xml document and using the provided root
	 * path to the model Java class files
	 * 
	 * @param xmlDoc
	 * @param rootPath
	 * @return
	 */
	public static SparkModel loadModel(Document xmlDoc, File rootPath)
			throws Exception {
		SparkModel model = null;
		ClassLoader classLoader = null;

		ArrayList<Node> nodes;
		Node root = xmlDoc.getFirstChild();
		Node node;

		if (root == null)
			throw new Exception("No root node in the xml document");

		/* Load class path */
		nodes = XmlDocUtils.getChildrenByTagName(root, "classpath");
		if (nodes.size() >= 1) {
			classLoader = setupClassPath(rootPath, nodes.get(0));
		}

		/* Load main model class */
		nodes = XmlDocUtils.getChildrenByTagName(root, "setup");
		if (nodes.size() != 1)
			throw new Exception("The model class must be uniquely specified");

		node = nodes.get(0);

		String setupName = node.getTextContent().trim();
		if (classLoader != null) {
			model = (SparkModel) classLoader.loadClass(setupName).newInstance();
		} else {
			model = (SparkModel) Class.forName(setupName).newInstance();
		}

		/* Load basic parameters */
		loadBasicModelParameters(model, root);

		/* Load agents */
		loadAgents(model, root, classLoader);

		/* Load variables */
		loadVariables(model, root);

		/* Load methods */
		loadMethods(model, root);

		return model;
	}
	

	/**
	 * Loads basic model parameters
	 * 
	 * @param model
	 * @param root
	 */
	private static void loadBasicModelParameters(SparkModel model, Node root) {
		/* Load tick size */
		RationalNumber tickTime = RationalNumber.parse(XmlDocUtils.getValue(
				root, "tick", "1"));

		// Set model's tick time
		model.setTickTime(tickTime);

		// Set model's default observer and execution mode
		ArrayList<Node> nodes = XmlDocUtils.getChildrenByTagName(root, "setup");

		String defaultObserver = XmlDocUtils.getValue(nodes.get(0), "observer",
				ObserverFactory.DEFAULT_OBSERVER_NAME);
		int defaultExecutionMode = ExecutionMode.SERIAL_MODE;
		try {
			defaultExecutionMode = ExecutionMode.parse(XmlDocUtils.getValue(
					nodes.get(0), "mode", "serial"));
		} catch (Exception e) {
			logger.error(e);
		}

		model.setDefaultObserver(defaultObserver, defaultExecutionMode);
	}
	

	/**
	 * Loads model variables
	 * 
	 * @param model
	 * @param root
	 */
	private static void loadVariables(SparkModel model, Node root) {
		ArrayList<Node> nodes = XmlDocUtils.getChildrenByTagName(root,
				"variables");
		if (nodes.size() > 0)
			nodes = XmlDocUtils.getChildrenByTagName(nodes.get(0), "variable");

		for (int i = 0; i < nodes.size(); i++) {
			ModelVariable var = null;

			try {
				var = ModelVariable.loadVariable(model, nodes.get(i));
			} catch (Exception e) {
				logger.error("Cannot create a variable");
				continue;
			}

			if (!model.addMovelVariable(var)) {
				logger.error("A variable " + var.getName()
						+ " is already declared");
			}
		}
	}
	

	/**
	 * Loads model methods
	 * 
	 * @param model
	 * @param root
	 */
	private static void loadMethods(SparkModel model, Node root) {
		ArrayList<Node> nodes = XmlDocUtils.getChildrenByTagName(root,
				"methods");
		if (nodes.size() > 0)
			nodes = XmlDocUtils.getChildrenByTagName(nodes.get(0), "method");

		for (int i = 0; i < nodes.size(); i++) {
			ModelMethod method = ModelMethod.loadMethod(model, nodes.get(i));
			if (!model.addMethod(method)) {
				logger.error("Method " + method.getName()
						+ " is already defined");
			}
		}
	}
	

	/**
	 * Loads definitions of all agents
	 * 
	 * @param node
	 */
	@SuppressWarnings("unchecked")
	private static void loadAgents(SparkModel model, Node root,
			ClassLoader classLoader) throws Exception {
		ArrayList<Node> list = XmlDocUtils.getChildrenByTagName(root, "agents");
		// No agents
		if (list.size() < 1)
			return;

		list = XmlDocUtils.getChildrenByTagName(list.get(0), "agent");
		Class<? extends Agent> agentType;

		for (int i = 0; i < list.size(); i++) {
			Node node = list.get(i);

			// Agent's priority
			int priority = XmlDocUtils.getIntegerValue(node, "priority",
					Observer.LOW_PRIORITY);
			// Agent's step time
			RationalNumber time = RationalNumber.parse(XmlDocUtils.getValue(
					node, "time", "1"));
			// Agent's class
			String className = node.getTextContent().trim();
			if (classLoader != null) {
				agentType = (Class<Agent>) classLoader.loadClass(className);
			} else {
				agentType = (Class<Agent>) Class.forName(className);
			}

			// Add agent's definition to the model
			model.addAgentType(agentType, time, priority);
		}
	}
	

	/**
	 * Returns the path derived from the 'path' attribute of the node and the
	 * path to the model xml file
	 * 
	 * @param node
	 * @return
	 */
	private static File getPath(File rootPath, Node node) {
		if (rootPath == null || node == null)
			return null;

		String path = XmlDocUtils.getValue(node, "path", null);

		if (path != null) {
			File path2 = new File(rootPath, path);
			return path2;
		}

		return null;
	}
	

	/**
	 * Returns a class loader for the model
	 * 
	 * @param node
	 */
	private static ClassLoader setupClassPath(File rootPath, Node node) {
		ClassLoader classLoader = null;
		if (rootPath == null)
			return null;

		File path = getPath(rootPath, node);

		try {
			if (path != null) {
				URI uri = path.toURI();
				classLoader = new URLClassLoader(new URL[] { uri.toURL() });
			}
		} catch (Exception e) {
			e.printStackTrace();
			classLoader = null;
		}

		return classLoader;
	}

}