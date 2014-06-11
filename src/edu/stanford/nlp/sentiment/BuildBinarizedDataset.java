package edu.stanford.nlp.sentiment;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import edu.stanford.nlp.io.IOUtils;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.HasWord;
import edu.stanford.nlp.parser.lexparser.LexicalizedParser;
import edu.stanford.nlp.parser.lexparser.TreeBinarizer;
import edu.stanford.nlp.process.DocumentPreprocessor;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.trees.Trees;
import edu.stanford.nlp.util.Generics;
import edu.stanford.nlp.util.Pair;

public class BuildBinarizedDataset {
  /**
   * Sets all of the labels on a tree to -1, representing that they
   * are the unknown class.
   */
  public static void setUnknownLabels(Tree tree) {
    if (tree.isLeaf()) {
      return;
    }

    for (Tree child : tree.children()) {
      setUnknownLabels(child);
    }

    tree.label().setValue("-1");
  }

  public static void extractLabels(Map<Pair<Integer, Integer>, String> spanToLabels, List<HasWord> tokens, String line) {
    String[] pieces = line.trim().split("\\s+");
    if (pieces.length == 0) {
      return;
    }
    if (pieces.length == 1) {
      String error = "Found line with label " + line + " but no tokens to associate with that line";
      System.err.println(error);
      throw new RuntimeException(error);
    }

    for (int i = 0; i < tokens.size() - pieces.length + 2; ++i) {
      boolean found = true;
      for (int j = 1; j < pieces.length; ++j) {
        if (!tokens.get(i + j - 1).word().equals(pieces[j])) {
          found = false;
          break;
        }
      }
      if (found) {
        spanToLabels.put(new Pair<Integer, Integer>(i, i + pieces.length - 1), pieces[0]);
      }
    }
  }

  public static boolean setSpanLabel(Tree tree, Pair<Integer, Integer> span, String value) {
    if (!(tree.label() instanceof CoreLabel)) {
      throw new AssertionError("Expected CoreLabels");
    }
    CoreLabel label = (CoreLabel) tree.label();
    if (label.get(CoreAnnotations.BeginIndexAnnotation.class) == span.first &&
        label.get(CoreAnnotations.EndIndexAnnotation.class) == span.second) {
      label.setValue(value);
      return true;
    }
    if (label.get(CoreAnnotations.BeginIndexAnnotation.class) > span.first &&
        label.get(CoreAnnotations.EndIndexAnnotation.class) < span.second) {
      return false;
    }
    for (Tree child : tree.children()) {
      if (setSpanLabel(child, span, value)) {
        return true;
      }
    }
    return false;
  }

  public static void main(String[] args) {
    CollapseUnaryTransformer transformer = new CollapseUnaryTransformer();

    String parserModel = "edu/stanford/nlp/models/lexparser/englishPCFG.ser.gz";

    String inputPath = null;

    for (int argIndex = 0; argIndex < args.length; ++argIndex) {
      if (args[argIndex].equalsIgnoreCase("-input")) {
        inputPath = args[argIndex + 1];
        argIndex += 2;
      } else if (args[argIndex].equalsIgnoreCase("-parserModel")) {
        parserModel = args[argIndex + 1];
        argIndex += 2;
      } else {
        System.err.println("Unknown argument " + args[argIndex]);
        System.exit(2);
      }
    }

    LexicalizedParser parser = LexicalizedParser.loadModel(parserModel);
    TreeBinarizer binarizer = new TreeBinarizer(parser.getTLPParams().headFinder(), parser.treebankLanguagePack(), 
                                                false, false, 0, false, false, 0.0, false, true, true);

    String text = IOUtils.slurpFileNoExceptions(inputPath);
    String[] chunks = text.split("\\n\\s*\\n+"); // need blank line to make a new chunk

    for (String chunk : chunks) {
      if (chunk.trim() == "") {
        continue;
      }
      // The exected format is that line 0 will be the text of the
      // sentence, and each subsequence line, if any, will be a value
      // followed by the sequence of tokens that get that value.

      // Here we take the first line and tokenize it as one sentence.
      String[] lines = chunk.trim().split("\\n");
      String sentence = lines[0];
      StringReader sin = new StringReader(sentence);
      DocumentPreprocessor document = new DocumentPreprocessor(sin);
      document.setSentenceFinalPuncWords(new String[] {"\n"});
      List<HasWord> tokens = document.iterator().next();

      System.err.println(tokens);

      Map<Pair<Integer, Integer>, String> spanToLabels = Generics.newHashMap();
      for (int i = 1; i < lines.length; ++i) {
        extractLabels(spanToLabels, tokens, lines[i]);
      }

      // TODO: add an option which treats the spans as constraints when parsing

      Tree tree = parser.apply(tokens);
      Tree binarized = binarizer.transformTree(tree);
      setUnknownLabels(binarized);
      Tree collapsedUnary = transformer.transformTree(binarized);

      Trees.convertToCoreLabels(collapsedUnary);
      collapsedUnary.indexSpans();

      for (Pair<Integer, Integer> span : spanToLabels.keySet()) {
        setSpanLabel(collapsedUnary, span, spanToLabels.get(span));
      }

      System.err.println(collapsedUnary);
      System.err.println();
    }
  }
}
