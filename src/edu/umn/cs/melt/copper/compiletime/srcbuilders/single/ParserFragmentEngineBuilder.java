package edu.umn.cs.melt.copper.compiletime.srcbuilders.single;

import edu.umn.cs.melt.copper.compiletime.builders.*;
import edu.umn.cs.melt.copper.compiletime.lrdfa.LRLookaheadAndLayoutSets;
import edu.umn.cs.melt.copper.compiletime.lrdfa.TransparentPrefixes;
import edu.umn.cs.melt.copper.compiletime.parsetable.LRParseTable;
import edu.umn.cs.melt.copper.compiletime.pipeline.ParserFragments;
import edu.umn.cs.melt.copper.compiletime.scannerdfa.GeneralizedDFA;
import edu.umn.cs.melt.copper.compiletime.scannerdfa.SingleScannerDFAAnnotations;
import edu.umn.cs.melt.copper.compiletime.spec.grammarbeans.*;
import edu.umn.cs.melt.copper.compiletime.spec.numeric.PSSymbolTable;
import edu.umn.cs.melt.copper.compiletime.spec.numeric.ParserSpec;
import edu.umn.cs.melt.copper.compiletime.spec.numeric.PrecedenceGraph;
import edu.umn.cs.melt.copper.main.ParserCompiler;
import edu.umn.cs.melt.copper.runtime.auxiliary.Pair;
import edu.umn.cs.melt.copper.runtime.auxiliary.internal.ByteArrayEncoder;
import edu.umn.cs.melt.copper.runtime.auxiliary.internal.QuotedStringFormatter;
import edu.umn.cs.melt.copper.runtime.engines.semantics.SpecialParserAttributes;
import edu.umn.cs.melt.copper.runtime.engines.single.ParserFragmentEngine;
import edu.umn.cs.melt.copper.runtime.engines.single.SingleDFAEngine;
import edu.umn.cs.melt.copper.runtime.engines.single.scanner.SingleDFAMatchData;
import edu.umn.cs.melt.copper.runtime.engines.single.semantics.SingleDFASemanticActionContainer;
import edu.umn.cs.melt.copper.runtime.io.InputPosition;
import edu.umn.cs.melt.copper.runtime.logging.CopperException;
import edu.umn.cs.melt.copper.runtime.logging.CopperParserException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.util.*;

/**
 * @author Kevin Viratyosin
 */
public class ParserFragmentEngineBuilder {

    private static final int MARKING_TERMINAL_FRAGMENT_ID = 0;
    private static final int HOST_FRAGMENT_ID = 0;
    private HostFragmentData hostFragment;
    private List<ExtensionFragmentData> extensionFragments;

    private int extensionCount;
    private int fragmentCount;
    private int hostTerminalLength;
    private int[] extTerminalLengths;

    private int[] extStateOffset;

    private GeneralizedDFA markingTerminalScannerDFA;
    private SingleScannerDFAAnnotations markingTerminalScannerDFAAnnotations;

    private int[][] parseTable;
    private int[][][] deltas;
    private int[][] cmaps;
    private int totalStateCount, hostStateCount;
    private ArrayList<MarkingTerminalData> markingTerminalDatas;
    private int markingTerminalOffset;
    private int markingTerminalCount;
    private Map<Integer, MarkingTerminalData> markingTerminalLayouts;
    private int markingTerminalLayoutCount;

    private int[][] extTerminalUses;
    private int[] hostTerminalUses;
    private BitSet[] layoutSets;
    private BitSet[] prefixSets;
    private BitSet[] markingTerminalLayoutSets;
    private BitSet[] markingTerminalEmptyStateSets;
    private BitSet[][] prefixMaps;
    private BitSet[][] markingTerminalEmptyPrefixMaps;
    private BitSet[] shiftableSets;
    private BitSet[] markingTerminalShiftableSets;
    private BitSet[] extShiftableUnion;
    private BitSet hostStateShiftableUnion;
    private BitSet markingTerminalShiftableUnion;

    private int totalProductionCount;
    private int[] productionLengths;
    private Map<Integer, Map<Integer, Integer>> productionMap;
    private Map<Integer, Pair<Integer, Integer>> productionMapBack;
    private int[] productionCounts;
    private int[] productionLHSs;
    private String rootType;
    private String errorType;
    private Map<Integer, Pair<Integer, Integer>> disambiguationFunctionMapBack;
    private int totalDisambiguationFunctionCount;
    private int extTableOffset;
    private ParserBean hostParser;

    private String[] markingTerminalSymbolNames;
    private String[][] symbolNames;
    private String[] markingTerminalSymbolDisplayNames;
    private String[][] symbolDisplayNames;

    private static class ObjectToHash {
        public Object obj;
        public String type;
        public String name;

        public ObjectToHash(Object obj, String type, String name) {
            this.obj = obj;
            this.type = type;
            this.name = name;
        }
    }

    private List<ObjectToHash> objectsToHash;

    public ParserFragmentEngineBuilder(ParserFragments fragments) {
        hostFragment = fragments.hostFragment;
        extensionFragments = fragments.extensionFragments;

        extensionCount = extensionFragments.size();
        fragmentCount = extensionCount + 1;

        hostTerminalLength = hostFragment.fullSpec.terminals.length();
        extTerminalLengths = new int[extensionCount];
        for (int e = 0; e < extensionCount; e++) {
            ExtensionMappingSpec spec = extensionFragments.get(e).extensionMappingSpec;
            extTerminalLengths[e] = spec.tableOffsetExtensionIndex(spec.extensionTerminalIndices.length());
        }

        extTableOffset = Math.max(hostTerminalLength, hostFragment.fullSpec.nonterminals.length());

        hostParser = hostFragment.symbolTable.getParser(hostFragment.fullSpec.parser);
    }

    public void buildEngine(
            PrintStream out,
            String packageDecl,
            String importDecls,
            String parserName,
            String scannerName,
            String parserAncillaries,
            String scannerAncillaries
    ) throws IOException, CopperException {
        printSignature(out);
        printDecls(out, packageDecl, importDecls);

        rootType = hostFragment.symbolTable.getNonTerminal(hostFragment.fullSpec.pr.getRHSSym(hostFragment.fullSpec.getStartProduction(), 0)).getReturnType();
        rootType = rootType == null ? Object.class.getName() : rootType;
        errorType = CopperParserException.class.getName();

        out.println("public class " + parserName + " extends " + ParserFragmentEngine.class.getName() + "<" + rootType + "," + errorType + "> {");

        makeObjectsToBeHashed();

        printFixedMethods(out);

        printFragmentMethods(out);

        // TODO, Terminals enum and pushToken ...?

        writeSemanticsClass(out);
        writeSemanticsClassUse(out);

        writeHashes(out);

        printParserAncillaryDecls(out);
        printParserAncillaryMethods(out);

        out.println(scannerAncillaries);

        writeStaticMemberInitializations(out);

        out.println("}");
    }
    
    private void generateSymbolNames() {
        markingTerminalSymbolNames = new String[markingTerminalCount + markingTerminalLayoutCount];
        symbolNames = new String[fragmentCount][];
        markingTerminalSymbolDisplayNames = new String[markingTerminalCount + markingTerminalLayoutCount];
        symbolDisplayNames = new String[fragmentCount][];

        for (int t = 0; t < markingTerminalCount; t++) {
            MarkingTerminalData data = markingTerminalDatas.get(t);
            markingTerminalSymbolNames[t] = generateVariableName(data.extensionId + 1, data.extensionTerminal);
            markingTerminalSymbolDisplayNames[t] = data.terminal.getDisplayName();
        }
        for (int t = 0; t < markingTerminalLayoutCount; t++) {
            MarkingTerminalData data = markingTerminalLayouts.get(t);
            markingTerminalSymbolNames[t + markingTerminalCount] = generateVariableName(data.extensionId + 1, data.extensionTerminal);
            markingTerminalSymbolDisplayNames[t + markingTerminalCount] = data.terminal.getDisplayName();
        }

        symbolNames[0] = new String[hostTerminalLength];
        symbolDisplayNames[0] = new String[hostTerminalLength];
        for (int t = hostFragment.fullSpec.terminals.nextSetBit(0); t >= 0; t = hostFragment.fullSpec.terminals.nextSetBit(t + 1)) {
            symbolNames[0][t] = generateVariableName(0, t);
            symbolDisplayNames[0][t] = hostFragment.symbolTable.get(t).getDisplayName();
        }

        for (int e = 0; e < extensionCount; e++) {
            ExtensionMappingSpec spec = extensionFragments.get(e).extensionMappingSpec;
            symbolNames[e + 1] = new String[extTerminalLengths[e]];
            symbolDisplayNames[e + 1] = new String[extTerminalLengths[e]];
            System.arraycopy(symbolNames[0], 0, symbolNames[e + 1], 0, hostTerminalLength);
            System.arraycopy(symbolDisplayNames[0], 0, symbolDisplayNames[e + 1], 0, hostTerminalLength);
            for (int t = spec.extensionTerminalIndices.nextSetBit(0); t >= 0; t = spec.extensionTerminalIndices.nextSetBit(t + 1)) {
                symbolNames[e + 1][spec.tableOffsetExtensionIndex(t)] = generateVariableName(e + 1, t);
                symbolDisplayNames[e + 1][spec.tableOffsetExtensionIndex(t)] = spec.extensionSymbolTable.get(t).getDisplayName();
            }
        }
    }

    // fragmentId == 0 => MARKING TERMINAL
    private String[] getSymbolNamesInclMT(int fragmentId) {
        if (fragmentId == MARKING_TERMINAL_FRAGMENT_ID) {
            return markingTerminalSymbolNames;
        } else {
            return getSymbolNames(fragmentId);
        }
    }

    // fragmentId == 0 => HOST symbol
    private String[] getSymbolNames(int fragmentId) {
        return symbolNames[fragmentId];
    }

    private void writeSemanticsClass(PrintStream out) {
        out.println("  public class Semantics extends " + SingleDFASemanticActionContainer.class.getName() + "<" + errorType + "> {");

        // TODO do extensions have semantic action aux code?
        String semanticActionAuxCode = hostParser.getSemanticActionAuxCode();
        // TODO do extensions have parser attributes? -- probably...
        for(int attrN = hostFragment.fullSpec.parserAttributes.nextSetBit(0); attrN >= 0; attrN = hostFragment.fullSpec.parserAttributes.nextSetBit(attrN + 1)) {
            ParserAttribute attr = hostFragment.symbolTable.getParserAttribute(attrN);
            out.println("    public " + attr.getAttributeType() + " " + generateVariableName(0, attrN) + ";");
        }

        out.println("    public Semantics() throws " + IOException.class.getName() + "," + errorType + " {");
        out.println("      runInit();");
        out.println("    }");

        out.println("    public void error(" + InputPosition.class.getName() + " pos," + String.class.getName() + " message) throws " + errorType + " {");
        out.println("      reportError(\"Error at \" + pos.toString() + \":\\n  \" + message);");
        out.println("    }");

        // TODO do extensions have default terminal code?
        out.println("    public void runDefaultTermAction() throws " + IOException.class.getName() + "," + errorType + " {");
        if (hostParser.getDefaultTerminalCode() != null) {
            out.println("      " + hostParser.getDefaultTerminalCode() + "");
        }
        out.println("    }");

        // TODO do extensions have default production code?
        out.println("    public void runDefaultProdAction() throws " + IOException.class.getName() + "," + errorType + " {");
        if (hostParser.getDefaultProductionCode() != null) {
            out.println("      " + hostParser.getDefaultProductionCode() + "");
        }
        out.println("    }");

        // TODO can extensions have parser init code?
        out.println("    public void runInit() throws " + IOException.class.getName() + "," + errorType + " {");
        if (hostParser.getParserInitCode() != null) {
            out.print("      " + hostParser.getParserInitCode());
        }
        // TODO can extensions have parser attributes? -- probably...
        for (int attrN = hostFragment.fullSpec.parserAttributes.nextSetBit(0); attrN >= 0; attrN = hostFragment.fullSpec.parserAttributes.nextSetBit(attrN+1)) {
            ParserAttribute attr = hostFragment.symbolTable.getParserAttribute(attrN);
            if (attr.getCode() != null) {
                out.println("      " + attr.getCode());
            }
        }
        out.println("    }");

        writeRunSemanticAction(out);
        writeRunProductionSemanticAction(out);
        writeRunTerminalSemanticAction(out);

        writeRunDisambiguationAction(out);
        writeRunDisambiguationActionMethods(out);

        // TODO can extensions have post parse code?
        out.println("    public void runPostParseCode(" + Object.class.getName() + " __root) {");
        if (hostParser.getPostParseCode() != null && !QuotedStringFormatter.isJavaWhitespace(hostParser.getPostParseCode())) {
            out.println("      " + rootType + " root = (" + rootType + ") __root;");
            out.println("      " + hostParser.getPostParseCode());
        }
        out.println("    }");

        out.println("  }");
    }

    private void writeRunSemanticAction(PrintStream out) {
        // Object runSemanticAction(InputPosition _pos, Object[] _children, int _prod)
        out.println("    public " + Object.class.getName() + " runSemanticAction(" + InputPosition.class.getName() + " _pos, " + Object.class.getName() + "[] _children, int _prod)");
        out.println("    throws " + IOException.class.getName() + "," + errorType + " {");
        out.println("      this._pos = _pos;");
        out.println("      this._children = _children;");
        out.println("      this._prod = _prod;");
        out.println("      this._specialAttributes = new " + SpecialParserAttributes.class.getName() + "(virtualLocation);");
        out.println("      " + Object.class.getName() + " RESULT = null;");
        out.println("      switch (_prod) {");
        for (Map.Entry<Integer, Pair<Integer, Integer>> entry : productionMapBack.entrySet()) {
            int p = entry.getKey();
            int fragment = entry.getValue().first();
            int fragmentIndex = entry.getValue().second();

            PSSymbolTable symbolTable = fragment == 0 ? hostFragment.symbolTable : extensionFragments.get(fragment - 1).extensionMappingSpec.extensionSymbolTable;
            if (fragment == 0 && fragmentIndex == hostFragment.fullSpec.getStartProduction()) {
                continue;
            }

            String productionCode = symbolTable.getProduction(fragmentIndex).getCode();
            if (productionCode != null && !QuotedStringFormatter.isJavaWhitespace(productionCode)) {
                out.println("        case " + p + ":");
                out.println("          RESULT = runSemanticAction_p" + p + "();");
                out.println("          break;");
            }
        }
        out.println("        default:");
        out.println("          runDefaultProdAction();");
        out.println("          break;");
        out.println("      }");
        out.println("      return RESULT;");
        out.println("    }");

        out.println("    public " + Object.class.getName() + " runSemanticAction(" + InputPosition.class.getName() + " _pos, " + SingleDFAMatchData.class.getName() + " _terminal)");
        out.println("    throws " + IOException.class.getName() + "," + errorType + " {");
        out.println("      throw new " + UnsupportedOperationException.class.getName() + "();");
        out.println("    }");

        // TODO -- probably a good idea to make sure that this actually works...
        out.println("    public " + Object.class.getName() + " runSemanticTerminalAction(int fragmentId, " + InputPosition.class.getName() + " _pos, " + SingleDFAMatchData.class.getName() + " _terminal)");
        out.println("    throws " + IOException.class.getName() + "," + errorType + " {");
        out.println("      this._pos = _pos;");
        out.println("      this._terminal = _terminal;");
        out.println("      this._specialAttributes = new " + SpecialParserAttributes.class.getName() + "(virtualLocation);");
        out.println("      String lexeme = _terminal.lexeme;");
        out.println("      " + Object.class.getName() + " RESULT = null;");
        out.println("      if (fragmentId == " + MARKING_TERMINAL_FRAGMENT_ID + ") {");
        out.println("        switch(_terminal.firstTerm) {");
        for (int t = 0; t < markingTerminalCount; t++) {
            String code = markingTerminalDatas.get(t).terminal.getCode();
            if (code != null && !QuotedStringFormatter.isJavaWhitespace(code)) {
                out.println("          case " + t + ":");
                out.println("            RESULT = runSemanticAction_mt_" + t + "(lexeme);");
                out.println("            break;");
            }
        }
        for (int t = 0; t < markingTerminalLayoutCount; t++) {
            String code = markingTerminalLayouts.get(t).terminal.getCode();
            if (code != null && !QuotedStringFormatter.isJavaWhitespace(code)) {
                out.println("          case " + (t + markingTerminalCount) + ":");
                out.println("            RESULT = runSemanticAction_mt_" + (t + markingTerminalCount) + "(lexeme);");
                out.println("            break;");
            }
        }
        out.println("          default:");
        out.println("            runDefaultTermAction();");
        out.println("            break;");
        out.println("        }");
        out.println("      } else {");
        out.println("        switch(_terminal.firstTerm) {");
        for (int t = hostFragment.fullSpec.terminals.nextSetBit(0); t >= 0; t = hostFragment.fullSpec.terminals.nextSetBit(t + 1)) {
            if (t != hostFragment.fullSpec.getEOFTerminal()) {
                String code = hostFragment.symbolTable.getTerminal(t).getCode();
                if (code != null && !QuotedStringFormatter.isJavaWhitespace(code)) {
                    out.println("          case " + t + ":");
                    out.println("            RESULT = runSemanticAction_th_" + t + "(lexeme);");
                    out.println("            break;");
                }
            }
        }
        BitSet extensionTerminalUnion = new BitSet();
        for (int e = 0; e < extensionCount; e++) {
            extensionTerminalUnion.or(extensionFragments.get(e).extensionMappingSpec.extensionTerminalIndices);
        }
        for (int t = extensionTerminalUnion.nextSetBit(0); t >= 0; t = extensionTerminalUnion.nextSetBit(t + 1)) {
            out.println("          case " + (t + extTableOffset) + ":");
            out.println("            switch (fragmentId - 1) {");
            for (int e = 0; e < extensionCount; e++) {
                if (extensionFragments.get(e).extensionMappingSpec.extensionTerminalIndices.get(t)) {
                    String code = extensionFragments.get(e).extensionMappingSpec.extensionSymbolTable.getTerminal(t).getCode();
                    if (code != null && !QuotedStringFormatter.isJavaWhitespace(code)) {
                        out.println("              case " + e + ":");
                        out.println("                RESULT = runSemanticAction_te" + e + "_" + t + "(lexeme);");
                        out.println("                break;");
                    }
                }
            }
            out.println("              default:");
            out.println("                runDefaultTermAction();");
            out.println("                break;");
            out.println("            }");
            out.println("            break;");
        }
        out.println("          default:");
        out.println("            runDefaultTermAction();");
        out.println("            break;");
        out.println("        }");
        out.println("      }");
        out.println("      return RESULT;");
        out.println("    }");
    }

    private void writeRunProductionSemanticAction(PrintStream out) {
        for (Map.Entry<Integer, Pair<Integer, Integer>> entry : productionMapBack.entrySet()) {
            int p = entry.getKey();
            int fragment = entry.getValue().first();
            int fragmentP = entry.getValue().second();

            boolean isExtension = fragment > 0;

            ExtensionMappingSpec extSpec = isExtension ? extensionFragments.get(fragment - 1).extensionMappingSpec : null;
            PSSymbolTable symbolTable = isExtension ? extSpec.extensionSymbolTable : hostFragment.symbolTable;
            ParserSpec.ProductionData pr = isExtension ? extSpec.pr : hostFragment.fullSpec.pr;

            if (fragment == 0 && fragmentP == hostFragment.fullSpec.getStartProduction()) {
                continue;
            }

            String productionCode = symbolTable.getProduction(fragmentP).getCode();
            if (productionCode == null || QuotedStringFormatter.isJavaWhitespace(productionCode)) {
                continue;
            }

            //String returnType = symbolTable.getNonTerminal(pr.getLHS(fragmentP)).getReturnType();
            String returnType = null;
            if (isExtension) {
                int tableLHS = pr.getLHS(fragmentP);
                if (tableLHS > extSpec.extensionSymbolTableOffset) {
                    returnType = symbolTable.getNonTerminal(tableLHS - extSpec.extensionSymbolTableOffset).getReturnType();
                } else {
                    returnType = hostFragment.symbolTable.getNonTerminal(tableLHS).getReturnType();
                }
            } else {
                returnType = symbolTable.getNonTerminal(pr.getLHS(fragmentP)).getReturnType();
            }
            returnType = returnType == null ? Object.class.getName() : returnType;

            out.println("    public " + returnType + " runSemanticAction_p" + p + "() throws " + errorType + " {");
            if (symbolTable.getProduction(fragmentP).getRhsVarNames() != null) {
                int k = 0;
                for (String var : symbolTable.getProduction(fragmentP).getRhsVarNames()) {
                    if(var != null) {
                        int sym = pr.getRHSSym(fragmentP, k);
                        String type = null;

                        if (isExtension && sym >= extSpec.extensionSymbolTableOffset) {
                            int extSym = sym - extSpec.extensionSymbolTableOffset;
                            if (extSpec.extensionTerminalIndices.get(extSym)) {
                                type = symbolTable.getTerminal(sym).getReturnType();
                            } else if (extSpec.extensionNonterminalIndices.get(extSym)) {
                                type = symbolTable.getNonTerminal(sym).getReturnType();
                            }
                        } else {
                            if (hostFragment.fullSpec.terminals.get(sym)) {
                                type = symbolTable.getTerminal(sym).getReturnType();
                            } else if (hostFragment.fullSpec.nonterminals.get(sym)) {
                                type = symbolTable.getNonTerminal(sym).getReturnType();
                            }
                        }
                        type = type == null ? Object.class.getName() : type;
                        out.print("            ");
                        String suppressWarnings = type.contains("<") ? "@SuppressWarnings(\"unchecked\") " : "";
                        out.println("      " + suppressWarnings + type + " " + var + " = (" + type + ") _children[" + k + "];");
                    }
                    k++;
                }
            }
            out.print("      " + returnType + " RESULT = null;\n");
            out.print("      " + productionCode + "\n");
            out.print("      return RESULT;\n");
            out.print("    }\n");
        }
    }

    private void writeRunTerminalSemanticAction(PrintStream out) {
        for (int t = 0; t < markingTerminalCount; t++) {
            String code = markingTerminalDatas.get(t).terminal.getCode();
            String returnType = markingTerminalDatas.get(t).terminal.getReturnType();
            if (code != null && !QuotedStringFormatter.isJavaWhitespace(code)) {
                out.println("    public " + returnType + " runSemanticAction_mt_" + t + "(final String lexeme)");
                out.println("    throws " + errorType + " {");
                out.println("      " + returnType + " RESULT = null;");
                out.println("      " + code + "");
                out.println("      return RESULT;");
                out.println("    }");
            }
        }
        for (int t = 0; t < markingTerminalLayoutCount; t++) {
            String code = markingTerminalLayouts.get(t).terminal.getCode();
            String returnType = markingTerminalLayouts.get(t).terminal.getReturnType();
            if (code != null && !QuotedStringFormatter.isJavaWhitespace(code)) {
                out.println("    public " + returnType + " runSemanticAction_mt_" + (t + markingTerminalCount) + "(final String lexeme)");
                out.println("    throws " + errorType + " {");
                out.println("      " + returnType + " RESULT = null;");
                out.println("      " + code + "");
                out.println("      return RESULT;");
                out.println("    }");
            }
        }
        for (int t = hostFragment.fullSpec.terminals.nextSetBit(0); t >= 0; t = hostFragment.fullSpec.terminals.nextSetBit(t + 1)) {
            if (t != hostFragment.fullSpec.getEOFTerminal()) {
                String code = hostFragment.symbolTable.getTerminal(t).getCode();
                String returnType = hostFragment.symbolTable.getTerminal(t).getReturnType();
                if (code != null && !QuotedStringFormatter.isJavaWhitespace(code)) {
                    out.println("    public " + returnType + " runSemanticAction_th_" + t + "(final String lexeme)");
                    out.println("    throws " + errorType + " {");
                    out.println("      " + returnType + " RESULT = null;");
                    out.println("      " + code + "");
                    out.println("      return RESULT;");
                    out.println("    }");
                }
            }
        }
        for (int e = 0; e < extensionCount; e++) {
            ExtensionMappingSpec extSpec = extensionFragments.get(e).extensionMappingSpec;
            for (int t = extSpec.extensionTerminalIndices.nextSetBit(0); t >= 0; t = extSpec.extensionTerminalIndices.nextSetBit(t + 1)) {
                String code = extSpec.extensionSymbolTable.getTerminal(t).getCode();
                String returnType = extSpec.extensionSymbolTable.getTerminal(t).getReturnType();
                if (code != null && !QuotedStringFormatter.isJavaWhitespace(code)) {
                    out.println("    public " + returnType + " runSemanticAction_te" + e + "_" + t + "(final String lexeme)");
                    out.println("    throws " + errorType + " {");
                    out.println("      " + returnType + " RESULT = null;");
                    out.println("      " + code + "");
                    out.println("      return RESULT;");
                    out.println("    }");
                }
            }
        }
    }

    private void writeRunDisambiguationAction(PrintStream out) {
        out.println("    public int runDisambiguationAction(" + InputPosition.class.getName() + " _pos," + SingleDFAMatchData.class.getName() + " match)");
        out.println("    throws " + IOException.class.getName() + "," + errorType + " {");
        out.println("      String lexeme = match.lexeme;");
        boolean first = true;
        for (Map.Entry<Integer, Pair<Integer, Integer>> entry : disambiguationFunctionMapBack.entrySet()) {
            int df = entry.getKey();
            int fragment = entry.getValue().first();
            int fragmentIndex = entry.getValue().second();

            String elseStr = "} else ";
            if (first) {
                elseStr = "";
                first = false;
            }
            out.println("      " + elseStr + "if (match.terms.equals(disambiguationGroups[" + df + "])) {");
            out.println("        return disambiguate_" + df + "(lexeme);");
        }
        if (first) {
            out.println("      return -1;");
        } else {
            out.println("      } else {");
            out.println("        return -1;");
            out.println("      }");
        }
        out.println("    }");
    }

    private void writeRunDisambiguationActionMethods(PrintStream out) {
        for (Map.Entry<Integer, Pair<Integer, Integer>> entry : disambiguationFunctionMapBack.entrySet()) {
            int df = entry.getKey();
            int fragment = entry.getValue().first();
            int fragmentIndex = entry.getValue().second();

            ParserSpec.DisambiguationFunctionData dfData = fragment == 0 ? hostFragment.fullSpec.df : extensionFragments.get(fragment - 1).extensionMappingSpec.df;

            out.println("    public int disambiguate_" + df + "(final String lexeme) throws " + errorType + " {");
            if (dfData.hasDisambiguateTo(fragmentIndex)) {
                out.println("      return /* " + getSymbolNames(fragment)[dfData.getDisambiguateTo(df)] + " */ " + dfData.getDisambiguateTo(df) + ";");
            } else {
                BitSet members = dfData.getMembers(fragmentIndex);
                for (int t = members.nextSetBit(0); t >= 0; t = members.nextSetBit(t + 1)) {
                    out.println("      @SuppressWarnings(\"unused\") final int " + getSymbolNames(fragment)[t] + " = " + t + ";");
                }
                PSSymbolTable symbolTable = fragment == 0 ? hostFragment.symbolTable : extensionFragments.get(fragment - 1).extensionMappingSpec.extensionSymbolTable;
                out.println("      " + symbolTable.getDisambiguationFunction(fragmentIndex).getCode());
            }
            out.println("    }");
        }
    }

    private void writeSemanticsClassUse(PrintStream out) {
        // TODO are these functions right in the interface ? Are the Fragment* functions correct?
        out.println("  public Semantics semantics;");

        out.println("  public " + Object.class.getName() + " runSemanticAction(" + InputPosition.class.getName() + " _pos," + Object.class.getName() + "[] _children,int _prod)");
        out.println("  throws " + IOException.class.getName() + "," + errorType + " {");
        out.println("    return semantics.runSemanticAction(_pos, _children, _prod);");
        out.println("  }");

        out.println("  public " + Object.class.getName() + " runFragmentSemanticAction(int fragmentId, " + InputPosition.class.getName() + " _pos," + SingleDFAMatchData.class.getName() + " _terminal)");
        out.println("  throws " + IOException.class.getName() + "," + errorType + " {");
        out.println("    return semantics.runSemanticTerminalAction(fragmentId, _pos, _terminal);");
        out.println("  }");

        // TOOD can extensions have post parse code?
        if (hostParser.getPostParseCode() != null && !QuotedStringFormatter.isJavaWhitespace(hostParser.getPostParseCode())) {
            out.println("  public void runPostParseCode(" + Object.class.getName() + " __root) {");
            out.println("    semantics.runPostParseCode(__root);");
            out.println("  }");
        }

        // Leaving in the fragmentId argument for now...
        out.println("  public int runFragmentDisambiguationAction(int fragmentId, " + InputPosition.class.getName() + " _pos," + SingleDFAMatchData.class.getName() + " matches)");
        out.println("  throws " + IOException.class.getName() + "," + errorType + " {");
        out.println("    return semantics.runDisambiguationAction(_pos,matches);");
        out.println("  }");

        out.println("  public " + SpecialParserAttributes.class.getName() + " getSpecialAttributes() {");
        out.println("    return semantics.getSpecialAttributes();");
        out.println("  }");

        out.println("  public void startEngine(" + InputPosition.class.getName() + " initialPos)");
        out.println("  throws " + IOException.class.getName() + "," + errorType + " {");
        out.println("     super.startEngine(initialPos);");
        out.println("     semantics = new Semantics();");
        out.println("  }");
    }

    private void printSignature(PrintStream out) {
        out.println("/*");
        out.println(" * Built at " + new java.util.Date(System.currentTimeMillis()));
        out.println(" * by Copper version " + ParserCompiler.VERSION);
        out.println(" *           build " + ParserCompiler.BUILD);
        out.println(" */");
    }

    private void printDecls(PrintStream out, String packageDecl, String importDecls) {
        out.println(packageDecl);
        out.println(importDecls);

        // TODO do extensions have preamble code?
        String preambleCode = hostParser.getPreambleCode();
        if (preambleCode != null) {
            out.println(preambleCode);
        }
    }

    private void printFixedMethods(PrintStream out) {
        out.println("  protected String formatError(String error) {");
        out.println("    String location = \"\";");
        out.println("    location += \"line \" + virtualLocation.getLine() + \", column \" + virtualLocation.getColumn();");
        out.println("    if (currentState.pos.getFileName().length() > 40) {");
        out.println("      location += \"\\n         \";");
        out.println("    }");
        out.println("    location += \" in file \" + virtualLocation.getFileName();");
        out.println("    location += \"\\n         (parser state: \" + currentState.statenum + \"; real character index: \" + currentState.pos.getPos() + \")\";");
        out.println("    return \"Error at \" + location + \":\\n  \" + error;");
        out.println("  }");

        out.println("  protected void reportError(String message) throws " + errorType + " {");
        out.println("    throw new " + CopperParserException.class.getName() + "(message);");
        out.println("  }");

        out.println("  protected void reportSyntaxError(int fragmentId) throws " + errorType + " {");
        out.println("    " + ArrayList.class.getName() + "<String> expectedTerminalsReal = bitVecToRealStringList(fragmentId, getFragmentShiftableSets(fragmentId)[currentState.statenum]);");
        out.println("    " + ArrayList.class.getName() + "<String> expectedTerminalsDisplay = bitVecToDisplayStringList(fragmentId, getFragmentShiftableSets(fragmentId)[currentState.statenum]);");
        out.println("    " + ArrayList.class.getName() + "<String> matchedTerminalsReal = bitVecToRealStringList(fragmentId, disjointMatch.terms);");
        out.println("    " + ArrayList.class.getName() + "<String> matchedTerminalsDisplay = bitVecToDisplayStringList(fragmentId, disjointMatch.terms);");
        out.println("    throw new edu.umn.cs.melt.copper.runtime.logging.CopperSyntaxError(virtualLocation,currentState.pos,currentState.statenum,expectedTerminalsReal,expectedTerminalsDisplay,matchedTerminalsReal,matchedTerminalsDisplay);");
        out.println("  }");
    }

    private void printFragmentMethods(PrintStream out) {
        out.println("  protected int getFragmentCount() {");
        out.println("    return " + fragmentCount + ";");
        out.println("  }");

        out.println("  protected int stateToFragmentId(int state) {");
        out.println("    if (state < " + hostStateCount + ") {");
        out.println("      return 0;");
        for (int e = 1; e < extensionCount; e++) {
            out.println("    } else if (state < " + extStateOffset[e] + ") {");
            out.println("      return " + e + ";");
        }
        out.println("    } else {");
        out.println("      return " + extensionCount + ";");
        out.println("    }");
        out.println("  }");

        out.println("  protected int getMarkingTerminalOffset() {");
        out.println("    return " + markingTerminalOffset + ";");
        out.println("  }");
    }

    private void printParserAncillaryDecls(PrintStream out) {
        for (ObjectToHash obj: objectsToHash) {
            out.println("  private static " + obj.type + " " + obj.name + ";");
        }
        out.println("  private static " + BitSet.class.getName() + "[] disambiguationGroups;");
    }

    private void printParserAncillaryMethods(PrintStream out) {
        out.println("  public int[][] getParseTable() {");
        out.println("    return parseTable;");
        out.println("  }");

        out.println("  protected String[] getSymbolNamesInclMT(int fragmentId) {");
        out.println("    if (fragmentId == " + MARKING_TERMINAL_FRAGMENT_ID + ") {");
        out.println("      return markingTerminalSymbolNames;");
        out.println("    } else {");
        out.println("      return symbolNames[fragmentId];");
        out.println("    }");
        out.println("  }");

        out.println("  protected String[] getSymbolDisplayNamesInclMT(int fragmentId) {");
        out.println("    if (fragmentId == " + MARKING_TERMINAL_FRAGMENT_ID + ") {");
        out.println("      return markingTerminalSymbolDisplayNames;");
        out.println("    } else {");
        out.println("      return symbolDisplayNames[fragmentId];");
        out.println("    }");
        out.println("  }");

        out.println("  protected int[] getProductionLengths() {");
        out.println("    return productionLengths;");
        out.println("  }");

        out.println("  public int[] getProductionLHSs() {");
        out.println("    return productionLHSs;");
        out.println("  }");

        out.println("  protected int transition(int fragmentId, int state, char ch) {");
        out.println("    return deltas[fragmentId][state][cmaps[fragmentId][ch]];");
        out.println("  }");

        out.println("  protected " + BitSet.class.getName() + "[] getFragmentAcceptSets(int fragmentId) {");
        out.println("    return acceptSetss[fragmentId];");
        out.println("  }");

        out.println("  protected " + BitSet.class.getName() + "[] getFragmentRejectSets(int fragmentId) {");
        out.println("    return rejectSetss[fragmentId];");
        out.println("  }");

        out.println("  protected " + BitSet.class.getName() + "[] getFragmentPossibleSets(int fragmentId) {");
        out.println("    return possibleSetss[fragmentId];");
        out.println("  }");

        out.println("  protected int getFragmentTerminalCount(int fragmentId) {");
        out.println("    if (fragmentId == " + MARKING_TERMINAL_FRAGMENT_ID + ") {");
        out.println("      return " + (markingTerminalCount + markingTerminalLayoutCount) + ";");
        out.println("    } else {");
        out.println("      return extTerminalLengths[fragmentId - 1];");
        out.println("    }");
        out.println("  }");

        // TODO convert to array access?
        out.println("  protected int getFragmentStartState(int fragmentId) {");
        out.println("    switch (fragmentId) {");
        out.println("      case 0:");
        out.println("        return " + markingTerminalScannerDFA.getStartState() + ";");
        for (int e = 0; e < extensionCount; e++) {
            out.println("      case " + (e+1) + ":");
            out.println("        return " + extensionFragments.get(e).scannerDFA.getStartState() + ";");
        }
        out.println("      default:");
        out.println("        return -1;"); // TODO fragment error
        out.println("    }");
        out.println("  }");

        out.println("  public int getPARSER_START_STATENUM() {");
        out.println("    return 1;"); // Looking at SingleDFAEngineBuilder -- this is always 1
        out.println("  }");

        out.println("  public int getEOF_SYMNUM() {");
        out.println("    return " + hostFragment.fullSpec.getEOFTerminal() + ";");
        out.println("  }");

        out.println("  public " + BitSet.class.getName() + "[][] getFragmentPrefixMaps(int fragmentId) {");
        out.println("    if (fragmentId == " + MARKING_TERMINAL_FRAGMENT_ID + ") {");
        out.println("      return markingTerminalEmptyPrefixMaps;");
        out.println("    } else {");
        out.println("      return prefixMaps;");
        out.println("    }");
        out.println("  }");


        out.println("  public " + BitSet.class.getName() + "[] getDisambiguationGroups() {");
        out.println("    return disambiguationGroups;");
        out.println("  }");

        out.println("  public " + BitSet.class.getName() + "[] getFragmentLayoutSets(int fragmentId) {");
        out.println("    if (fragmentId == " + MARKING_TERMINAL_FRAGMENT_ID + ") {");
        out.println("      return markingTerminalLayoutSets;");
        out.println("    } else {");
        out.println("      return layoutSets;");
        out.println("    }");
        out.println("  }");

        out.println("  public " + BitSet.class.getName() + "[] getFragmentPrefixSets(int fragmentId) {");
        out.println("    if (fragmentId == " + MARKING_TERMINAL_FRAGMENT_ID + ") {");
        out.println("      return markingTerminalEmptyStateSets;");
        out.println("    } else {");
        out.println("      return prefixSets;");
        out.println("    }");
        out.println("  }");

        out.println("  protected int getFragmentTerminalUses(int fragmentId, int t) {");
        out.println("    if (fragmentId == " + MARKING_TERMINAL_FRAGMENT_ID + ") {");
        out.println("      return t < " + markingTerminalCount + " ? " + SingleDFAEngine.TERMINAL_EXCLUSIVELY_SHIFTABLE + " : " + SingleDFAEngine.TERMINAL_EXCLUSIVELY_LAYOUT + ";");
        out.println("    } else if (t >= hostTerminalUses.length) {");
        out.println("      return extTerminalUses[fragmentId - 1][t];");
        out.println("    } else {");
        out.println("      return hostTerminalUses[t] & extTerminalUses[fragmentId - 1][t];");
        out.println("    }");
        out.println("  }");

        out.println("  public " + BitSet.class.getName() + " getFragmentShiftableUnion(int fragmentId) {");
        out.println("    if (fragmentId == " + MARKING_TERMINAL_FRAGMENT_ID + ") {");
        out.println("      return markingTerminalShiftableUnion;");
        out.println("    } else {");
        out.println("      return extShiftableUnion[fragmentId - 1];");
        out.println("    }");
        out.println("  }");

        out.println("  public " + BitSet.class.getName() + "[] getFragmentShiftableSets(int fragmentId) {");
        out.println("    if (fragmentId == " + MARKING_TERMINAL_FRAGMENT_ID + ") {");
        out.println("      return markingTerminalShiftableSets;");
        out.println("    } else {");
        out.println("      return shiftableSets;");
        out.println("    }");
        out.println("  }");
    }

    private void makeObjectsToBeHashed() {
        objectsToHash = new ArrayList<ObjectToHash>();

        objectsToHash.add(new ObjectToHash(extTerminalLengths, "int[]", "extTerminalLengths"));

        prepProductionIndices();
        prepDisambiguationFunctionIndices();

        makeParseTableAndSets();
        objectsToHash.add(new ObjectToHash(parseTable, "int[][]", "parseTable"));
        objectsToHash.add(new ObjectToHash(hostTerminalUses, "int[]", "hostTerminalUses"));
        objectsToHash.add(new ObjectToHash(extTerminalUses, "int[][]", "extTerminalUses"));
        objectsToHash.add(new ObjectToHash(markingTerminalEmptyStateSets, BitSet.class.getName() + "[]", "markingTerminalEmptyStateSets"));
        objectsToHash.add(new ObjectToHash(markingTerminalLayoutSets, BitSet.class.getName() + "[]", "markingTerminalLayoutSets"));
        objectsToHash.add(new ObjectToHash(layoutSets, BitSet.class.getName() + "[]", "layoutSets"));
        objectsToHash.add(new ObjectToHash(prefixSets, BitSet.class.getName() + "[]", "prefixSets"));
        objectsToHash.add(new ObjectToHash(prefixMaps, BitSet.class.getName() + "[][]", "prefixMaps"));
        objectsToHash.add(new ObjectToHash(markingTerminalEmptyPrefixMaps, BitSet.class.getName() + "[][]", "markingTerminalEmptyPrefixMaps"));
        objectsToHash.add(new ObjectToHash(shiftableSets, BitSet.class.getName() + "[]", "shiftableSets"));
        objectsToHash.add(new ObjectToHash(markingTerminalShiftableSets, BitSet.class.getName() + "[]", "markingTerminalShiftableSets"));
        objectsToHash.add(new ObjectToHash(extShiftableUnion, BitSet.class.getName() + "[]", "extShiftableUnion"));
        objectsToHash.add(new ObjectToHash(markingTerminalShiftableUnion, BitSet.class.getName(), "markingTerminalShiftableUnion"));

        generateMarkingTerminalScanner();

        deltas = new int[fragmentCount][][];
        cmaps = new int[fragmentCount][];
        deltas[0] = markingTerminalScannerDFA.getTransitions();
        cmaps[0] = markingTerminalScannerDFAAnnotations.charMap;
        for (int i = 0; i < extensionCount; i++) {
            deltas[i + 1] = extensionFragments.get(i).scannerDFA.getTransitions();
            cmaps[i + 1] = extensionFragments.get(i).scannerDFAAnnotations.charMap;
        }
        objectsToHash.add(new ObjectToHash(deltas, "int[][][]", "deltas"));
        objectsToHash.add(new ObjectToHash(cmaps, "int[][]", "cmaps"));

        addScannerAnnotationsToBeHashed();

        makeProductionLengths();
        objectsToHash.add(new ObjectToHash(productionLengths, "int[]", "productionLengths"));

        makeProductionLHSs();
        objectsToHash.add(new ObjectToHash(productionLHSs, "int[]", "productionLHSs"));

        generateSymbolNames();
        objectsToHash.add(new ObjectToHash(symbolNames, "String[][]", "symbolNames"));
        objectsToHash.add(new ObjectToHash(markingTerminalSymbolNames, "String[]", "markingTerminalSymbolNames"));
        objectsToHash.add(new ObjectToHash(symbolDisplayNames, "String[][]", "symbolDisplayNames"));
        objectsToHash.add(new ObjectToHash(markingTerminalSymbolDisplayNames, "String[]", "markingTerminalSymbolDisplayNames"));
    }

    private int prepElementIndices(
            Map<Integer, Map<Integer, Integer>> map,
            Map<Integer, Pair<Integer, Integer>> mapBack,
            int[] counts,
            BitSet hostElements,
            BitSet[] extensionElements
    ) {
        int newIndex = 0;

        counts[0] = hostElements.cardinality();
        map.put(0, new TreeMap<Integer, Integer>());
        for (int x = hostElements.nextSetBit(0); x >= 0; x = hostElements.nextSetBit(x + 1)) {
            map.get(0).put(x, newIndex);
            mapBack.put(newIndex, new Pair<Integer, Integer>(0, x));
            newIndex += 1;
        }
        for (int e = 0; e < extensionCount; e++) {
            BitSet elements = extensionElements[e];
            counts[e + 1] = elements.cardinality();
            map.put(e + 1, new TreeMap<Integer, Integer>());
            for (int p = elements.nextSetBit(0); p >= 0; p = elements.nextSetBit(p + 1)) {
                map.get(e + 1).put(p, newIndex);
                mapBack.put(newIndex, new Pair<Integer, Integer>(e + 1, p));
                newIndex += 1;
            }
        }

        int totalElementCount = newIndex;
        return totalElementCount;
    }

    private void prepProductionIndices() {
        productionMap = new TreeMap<Integer, Map<Integer, Integer>>();
        productionMapBack = new TreeMap<Integer, Pair<Integer, Integer>>();
        productionCounts = new int[fragmentCount];

        BitSet[] extProductions = new BitSet[extensionCount];
        for (int e = 0; e < extensionCount; e++) {
            extProductions[e] = extensionFragments.get(e).extensionMappingSpec.extensionProductionIndices;
        }
        totalProductionCount = prepElementIndices(productionMap, productionMapBack, productionCounts, hostFragment.fullSpec.productions, extProductions);
    }

    private void prepDisambiguationFunctionIndices() {
        Map<Integer, Map<Integer, Integer>> disambiguationFunctionMap = new TreeMap<Integer, Map<Integer, Integer>>();
        disambiguationFunctionMapBack = new TreeMap<Integer, Pair<Integer, Integer>>();
        int[] disambiguationFunctionCounts = new int[fragmentCount];

        BitSet[] extDisambiguationFunctions = new BitSet[extensionCount];
        for (int e = 0; e < extensionCount; e++) {
            extDisambiguationFunctions[e] = extensionFragments.get(e).extensionMappingSpec.extensionDisambiguationFunctionIndices;
        }
        totalDisambiguationFunctionCount = prepElementIndices(
                disambiguationFunctionMap,
                disambiguationFunctionMapBack,
                disambiguationFunctionCounts,
                hostFragment.fullSpec.disambiguationFunctions,
                extDisambiguationFunctions
        );
    }

    private void addScannerAnnotationsToBeHashed() {
        BitSet[][] acceptSetss = new BitSet[fragmentCount][];
        acceptSetss[0] = markingTerminalScannerDFAAnnotations.acceptSets;
        for (int e = 0; e < extensionCount; e++) {
            acceptSetss[e + 1] = extensionFragments.get(e).scannerDFAAnnotations.acceptSets;
        }
        objectsToHash.add(new ObjectToHash(acceptSetss, BitSet.class.getName() + "[][]", "acceptSetss"));

        BitSet[][] rejectSetss = new BitSet[fragmentCount][];
        rejectSetss[0] = markingTerminalScannerDFAAnnotations.rejectSets;
        for (int e = 0; e < extensionCount; e++) {
            rejectSetss[e + 1] = extensionFragments.get(e).scannerDFAAnnotations.rejectSets;
        }
        objectsToHash.add(new ObjectToHash(rejectSetss, BitSet.class.getName() + "[][]", "rejectSetss"));

        BitSet[][] possibleSetss = new BitSet[fragmentCount][];
        possibleSetss[0] = markingTerminalScannerDFAAnnotations.possibleSets;
        for (int e = 0; e < extensionCount; e++) {
            possibleSetss[e + 1] = extensionFragments.get(e).scannerDFAAnnotations.possibleSets;
        }
        objectsToHash.add(new ObjectToHash(possibleSetss, BitSet.class.getName() + "[][]", "possibleSetss"));
    }

    private static class MarkingTerminalData implements Comparable {
        public int extensionId;
        public int extensionTerminal;
        public int hostLHS;
        public int offsetTransitionState;
        public int endIndex;
        public Terminal terminal;
        public MarkingTerminalData(int extensionId, int extensionTerminal, int hostLHS, int offsetTransitionState, int endIndex, Terminal terminal) {
            this.extensionId = extensionId;
            this.extensionTerminal = extensionTerminal;
            this.hostLHS = hostLHS;
            this.offsetTransitionState = offsetTransitionState;
            this.endIndex = endIndex;
            this.terminal = terminal;
        }

        @Override
        public int compareTo(Object o) {
            return this.endIndex - ((MarkingTerminalData) o).endIndex;
        }
    }

    // Must be called after makeParseTable
    private void generateMarkingTerminalScanner() {
        Collections.sort(markingTerminalDatas);
        TreeMap<Integer, Regex> regexes = new TreeMap<Integer, Regex>();
        BitSet terminals = new BitSet();

        for (int t = 0; t < markingTerminalCount; t++) {
            MarkingTerminalData data = markingTerminalDatas.get(t);
            regexes.put(t, extensionFragments.get(data.extensionId).markingTerminalRegexes.get(data.extensionTerminal));
            terminals.set(t);
        }
        for (int l = 0; l < markingTerminalLayoutCount; l++) {
            regexes.put(markingTerminalCount + l, markingTerminalLayouts.get(l).terminal.getRegex());
            terminals.set(markingTerminalCount + l);
        }

        markingTerminalScannerDFA = SingleScannerDFABuilder.build(regexes, terminals, -1);
        markingTerminalScannerDFAAnnotations = SingleScannerDFAAnnotationBuilder.build(new PrecedenceGraph(markingTerminalCount + markingTerminalLayoutCount), markingTerminalScannerDFA);
    }

    private void makeParseTableAndSets() {
        hostStateCount = hostFragment.parseTable.size();
        totalStateCount = hostStateCount;
        int maxTableSymbols = hostFragment.fullSpec.nonterminals.length();
        markingTerminalDatas = new ArrayList<MarkingTerminalData>();

        extStateOffset = new int[extensionCount];
        for (int i = 0; i < extensionCount; i++) {
            ExtensionFragmentData fragment = extensionFragments.get(i);
            ExtensionMappingSpec spec = extensionFragments.get(i).extensionMappingSpec;

            extStateOffset[i] = totalStateCount;
            totalStateCount += fragment.appendedExtensionTable.size();
            int extTableWidth = spec.tableOffsetExtensionIndex(spec.extensionNonterminalIndices.length());
            if (extTableWidth > maxTableSymbols) {
                maxTableSymbols = extTableWidth;
            }
        }

        markingTerminalOffset = maxTableSymbols;
        for (int i = 0; i < extensionCount; i++) {
            ExtensionFragmentData fragment = extensionFragments.get(i);
            Set<Integer> markingTerminals = fragment.markingTerminalLHS.keySet();
            for (int mt: markingTerminals) {
                int lhs = fragment.markingTerminalLHS.get(mt);
                int offsetState = fragment.markingTerminalStates.get(mt) + extStateOffset[i];
                Terminal terminal = fragment.extensionMappingSpec.extensionSymbolTable.getTerminal(mt);
                markingTerminalDatas.add(new MarkingTerminalData(i, mt, lhs, offsetState, maxTableSymbols, terminal));
                maxTableSymbols += 1;
            }
        }
        markingTerminalCount = markingTerminalDatas.size();

        parseTable = new int[totalStateCount][maxTableSymbols];
        layoutSets = new BitSet[totalStateCount];
        prefixSets = new BitSet[totalStateCount];
        prefixMaps = new BitSet[totalStateCount][];
        shiftableSets = new BitSet[totalStateCount];

        hostTerminalUses = new int[hostTerminalLength];
        hostStateShiftableUnion = SingleDFAEngine.newBitVec(hostTerminalLength);
        copyParseTable(-1);

        extTerminalUses = new int[extensionCount][];
        extShiftableUnion = new BitSet[extensionCount];
        for (int i = 0; i < extensionCount; i++) {
            ExtensionFragmentData fragment = extensionFragments.get(i);
            ExtensionMappingSpec spec = extensionFragments.get(i).extensionMappingSpec;

            extTerminalUses[i] = new int[extTerminalLengths[i]];
            extShiftableUnion[i] = SingleDFAEngine.newBitVec(extTerminalLengths[i]);
            copyParseTable(i);
            extShiftableUnion[i].or(hostStateShiftableUnion);
        }

        markingTerminalLayoutSets = new BitSet[totalStateCount];
        fillMarkingTerminalMetaData(markingTerminalDatas);

        markingTerminalEmptyStateSets = new BitSet[totalStateCount];
        markingTerminalEmptyPrefixMaps = new BitSet[totalStateCount][markingTerminalCount + markingTerminalLayoutCount];
        BitSet empty = SingleDFAEngine.newBitVec(markingTerminalCount);
        for (int state = 0; state < totalStateCount; state++) {
            markingTerminalEmptyStateSets[state] = empty;
            for (int t = 0; t < markingTerminalCount + markingTerminalLayoutCount; t++) {
                markingTerminalEmptyPrefixMaps[state][t] = empty;
            }
        }
    }

    private void copyParseTable(int extensionId) {
        LRParseTable table = null;
        BitSet hostTerminals = hostFragment.fullSpec.terminals;
        BitSet hostNonterminals = hostFragment.fullSpec.nonterminals;
        ExtensionFragmentData extensionFragmentData = null;
        ExtensionMappingSpec extSpec = null;
        BitSet extTerminals = null;
        BitSet extNonterminals = null;
        BitSet offsetExtNonterminals = null;
        LRLookaheadAndLayoutSets layouts = null;
        TransparentPrefixes prefixes = null;
        BitSet shiftableUnion = null;

        boolean isExtension = extensionId >= 0;
        int stateOffset = 0;
        if (isExtension) {
            extensionFragmentData = extensionFragments.get(extensionId);
            table = extensionFragmentData.appendedExtensionTable;
            extSpec = extensionFragmentData.extensionMappingSpec;
            extTerminals = extSpec.extensionTerminalIndices;
            extNonterminals = extSpec.extensionNonterminalIndices;

            offsetExtNonterminals = new BitSet();
            for (int nt = extNonterminals.nextSetBit(0); nt >= 0; nt = extNonterminals.nextSetBit(nt + 1)) {
                offsetExtNonterminals.set(extSpec.tableOffsetExtensionIndex(nt));
            }

            stateOffset = extStateOffset[extensionId];
            layouts = extensionFragmentData.extensionLookaheadAndLayoutSets;
            prefixes = extensionFragmentData.transparentPrefixes;
            shiftableUnion = extShiftableUnion[extensionId];
        } else {
            table = hostFragment.parseTable;
            layouts = hostFragment.lookaheadSets;
            prefixes = hostFragment.prefixes;
            shiftableUnion = hostStateShiftableUnion;
        }

        int symType;
        for (int state = 0; state < table.size(); state++) {
            for (int t = table.getValidLA(state).nextSetBit(0); t >= 0; t = table.getValidLA(state).nextSetBit(t+1)) {
                boolean isTerminal, isNonterminal;
                if (extSpec != null) {
                    isTerminal = extSpec.isTableOffsetTerminal(t);
                    isNonterminal = extSpec.isTableOffsetNonterminal(t);
                } else {
                    isTerminal = hostTerminals.get(t);
                    isNonterminal = hostNonterminals.get(t);
                }

                int actionParameter = table.getActionParameter(state, t);

                if (isTerminal) {
                    byte actionType = table.getActionType(state, t);

                    if (actionType == LRParseTable.ERROR) {
                        symType = SingleDFAEngine.STATE_ERROR;
                    } else if (actionType == LRParseTable.ACCEPT && t == hostFragment.fullSpec.getEOFTerminal()) {
                        symType = SingleDFAEngine.STATE_ACCEPT;
                    } else if (actionType == LRParseTable.SHIFT) {
                        symType = SingleDFAEngine.STATE_SHIFT;
                        if (isExtension && actionParameter < 0) { // ext state
                            actionParameter = ExtensionMappingSpec.decodeExtensionIndex(actionParameter) + extStateOffset[extensionId];
                        }
                    } else if (actionType == LRParseTable.REDUCE) {
                        symType = SingleDFAEngine.STATE_REDUCE;
                        if (isExtension && actionParameter < 0) {
                            actionParameter = productionMap.get(extensionId + 1).get(ExtensionMappingSpec.decodeExtensionIndex(actionParameter));
                        } else {
                            actionParameter = productionMap.get(0).get(actionParameter);
                        }
                    } else {
                        symType = SingleDFAEngine.STATE_ERROR;
                    }

                    parseTable[state + stateOffset][t] = SingleDFAEngine.newAction(symType, actionParameter);
                    if (isExtension) {
                        extTerminalUses[extensionId][t] &= SingleDFAEngine.TERMINAL_EXCLUSIVELY_SHIFTABLE;
                    } else {
                        hostTerminalUses[t] &= SingleDFAEngine.TERMINAL_EXCLUSIVELY_SHIFTABLE;
                    }
                } else if (isNonterminal) {
                    if (isExtension && actionParameter < 0) { // ext state
                        actionParameter = ExtensionMappingSpec.decodeExtensionIndex(actionParameter) + extStateOffset[extensionId];
                    }
                    parseTable[state + stateOffset][t] = SingleDFAEngine.newAction(SingleDFAEngine.STATE_GOTO, actionParameter);
                }
            }

            shiftableSets[state + stateOffset] = SingleDFAEngine.newBitVec(isExtension ? extTerminalLengths[extensionId] : hostTerminalLength);
            shiftableSets[state + stateOffset].or(table.getValidLA(state));

            layoutSets[state + stateOffset] = SingleDFAEngine.newBitVec(isExtension ? extTerminalLengths[extensionId] : hostTerminalLength);
            layoutSets[state + stateOffset].or(layouts.getLayout(state));
            shiftableSets[state + stateOffset].or(layouts.getLayout(state));

            prefixSets[state + stateOffset] = SingleDFAEngine.newBitVec(isExtension ? extTerminalLengths[extensionId] : hostTerminalLength);
            prefixSets[state + stateOffset].or(prefixes.getPrefixes(state));
            shiftableSets[state + stateOffset].or(prefixes.getPrefixes(state));

            shiftableSets[state + stateOffset].andNot(hostNonterminals);
            if (isExtension) {
                shiftableSets[state + stateOffset].andNot(offsetExtNonterminals);
            }

            shiftableUnion.or(shiftableSets[state + stateOffset]);

            for (int layout = layouts.getLayout(state).nextSetBit(0); layout >= 0; layout = layouts.getLayout(state).nextSetBit(layout + 1)) {
                if (isExtension) {
                    extTerminalUses[extensionId][layout] &= SingleDFAEngine.TERMINAL_EXCLUSIVELY_LAYOUT;
                } else {
                    hostTerminalUses[layout] &= SingleDFAEngine.TERMINAL_EXCLUSIVELY_LAYOUT;
                }
            }

            prefixMaps[state + stateOffset] = new BitSet[isExtension ? extTerminalLengths[extensionId] : hostTerminalLength];
            for (int prefix = prefixes.getPrefixes(state).nextSetBit(0); prefix >= 0; prefix = prefixes.getPrefixes(state).nextSetBit(prefix + 1)) {
                if (isExtension) {
                    extTerminalUses[extensionId][prefix] &= SingleDFAEngine.TERMINAL_EXCLUSIVELY_PREFIX;
                } else {
                    hostTerminalUses[prefix] &= SingleDFAEngine.TERMINAL_EXCLUSIVELY_PREFIX;
                }
                if (prefix != hostFragment.fullSpec.getEOFTerminal()) {
                    prefixMaps[state + stateOffset][prefix] = SingleDFAEngine.newBitVec(isExtension ? extTerminalLengths[extensionId] : hostTerminalLength);
                    prefixMaps[state + stateOffset][prefix].or(prefixes.getFollowingTerminals(state, prefix));
                }
            }
        }
    }

    private void fillMarkingTerminalMetaData(List<MarkingTerminalData> markingTerminalDatas) {
        markingTerminalLayouts = new TreeMap<Integer, MarkingTerminalData>();
        Map<Integer, Map<Integer, Integer>> markingTerminalLayoutsByFragment = new TreeMap<Integer, Map<Integer, Integer>>();
        markingTerminalLayoutCount = 0;

        markingTerminalShiftableSets = new BitSet[totalStateCount];

        for (int i = 0; i < fragmentCount; i++) {
            markingTerminalLayoutsByFragment.put(i, new TreeMap<Integer, Integer>());
        }

        fillFragmentStatesWithMTData(-1, markingTerminalLayoutsByFragment.get(0)); // HOST
        for (int extensionId = 0; extensionId < extensionCount; extensionId++) {
            fillFragmentStatesWithMTData(extensionId, markingTerminalLayoutsByFragment.get(extensionId + 1));
        }

        markingTerminalShiftableUnion = SingleDFAEngine.newBitVec(markingTerminalCount + markingTerminalLayoutCount);
        markingTerminalShiftableUnion.set(0, markingTerminalCount + markingTerminalLayoutCount);
    }

    // extensionId = -1 for host
    private void fillFragmentStatesWithMTData(int extensionId, Map<Integer, Integer> fragmentMarkingTerminalLayouts) {
        boolean isExtension = extensionId >= 0;
        LRParseTable table = isExtension ? extensionFragments.get(extensionId).appendedExtensionTable : hostFragment.parseTable;
        BitSet[] initNTs = isExtension ? extensionFragments.get(extensionId).initNTs : hostFragment.initNTs;
        Map<Integer, Map<Integer, Set<Integer>>> laSources = isExtension ? extensionFragments.get(extensionId).laSources : hostFragment.laSources;
        int stateOffset = isExtension ? extStateOffset[extensionId] : 0;

        for (int state = 0; state < table.size(); state++) {
            int offsetState = state + stateOffset;

            markingTerminalShiftableSets[offsetState] = SingleDFAEngine.newBitVec(markingTerminalCount + markingTerminalLayoutCount);
            markingTerminalLayoutSets[offsetState] = SingleDFAEngine.newBitVec(markingTerminalCount + markingTerminalLayoutCount);

            BitSet stateInitNTs = initNTs[state];
            for (int nt = stateInitNTs.nextSetBit(0); nt >= 0; nt = stateInitNTs.nextSetBit(nt + 1)) {
                for (MarkingTerminalData mtData: markingTerminalDatas) {
                    if (nt == mtData.hostLHS) {
                        parseTable[offsetState][mtData.endIndex] = SingleDFAEngine.newAction(SingleDFAEngine.STATE_SHIFT, mtData.offsetTransitionState);
                        fillMTLayoutData(state, offsetState, mtData, fragmentMarkingTerminalLayouts, extensionId);
                    }
                }
            }
            Map<Integer, Set<Integer>> stateLASources = laSources.get(state);
            for (MarkingTerminalData mtData: markingTerminalDatas) {
                Set<Integer> productions = stateLASources.get(mtData.hostLHS);
                if (productions != null && !productions.isEmpty()) {
                    for (int production: productions) {
                        int newProductionIndex = 0;
                        if (production < 0) { // ext production
                            newProductionIndex = productionMap.get(extensionId + 1).get(ExtensionMappingSpec.decodeExtensionIndex(production));
                        } else {
                            newProductionIndex = productionMap.get(0).get(production);
                        }
                        parseTable[offsetState][mtData.endIndex] = SingleDFAEngine.newAction(SingleDFAEngine.STATE_REDUCE, newProductionIndex);
                        fillMTLayoutData(state, offsetState, mtData, fragmentMarkingTerminalLayouts, extensionId);
                    }
                }
            }
        }
    }

    private void fillMTLayoutData(int state, int offsetState, MarkingTerminalData mtData, Map<Integer, Integer> fragmentMarkingTerminalLayouts, int extensionId) {
        boolean isExtension = extensionId >= 0;
        PSSymbolTable symbolTable = isExtension ? extensionFragments.get(extensionId).extensionMappingSpec.extensionSymbolTable : hostFragment.symbolTable;
        LRLookaheadAndLayoutSets fragmentLayoutSets = isExtension ? extensionFragments.get(extensionId).extensionLookaheadAndLayoutSets : hostFragment.lookaheadSets;

        markingTerminalShiftableSets[offsetState].set(mtData.endIndex - markingTerminalOffset);
        BitSet layouts = fragmentLayoutSets.getLayout(state);

        for (int l = layouts.nextSetBit(0); l >= 0; l = layouts.nextSetBit(l + 1)) {
            if (!fragmentMarkingTerminalLayouts.containsKey(l)) {
                MarkingTerminalData layout;
                if (l < extTableOffset) {
                    layout = new MarkingTerminalData(-1, l, -1, -1, -1, hostFragment.symbolTable.getTerminal(l));
                } else {
                    layout = new MarkingTerminalData(extensionId, l - extTableOffset, -1, -1, -1, symbolTable.getTerminal(l - extTableOffset));
                }
                markingTerminalLayouts.put(markingTerminalLayoutCount, layout);
                fragmentMarkingTerminalLayouts.put(l, markingTerminalLayoutCount);
                markingTerminalLayoutCount += 1;
            }
            markingTerminalShiftableSets[offsetState].set(fragmentMarkingTerminalLayouts.get(l) + markingTerminalCount);
            markingTerminalLayoutSets[offsetState].set(fragmentMarkingTerminalLayouts.get(l) + markingTerminalCount);
        }
    }

    private boolean isHostFragment(int fragmentId) {
        return fragmentId == 0;
    }

    private int getFragmentIdFromState(int state) {
        int fragmentId = 0;
        for (int i = 0; i < extensionCount; i++) {
            if (state < extStateOffset[i]) {
                break;
            } else {
                fragmentId += 1;
            }
        }
        return fragmentId;
    }

    private void makeProductionLengths() {
        productionLengths = new int[totalProductionCount];

        for (Map.Entry<Integer, Pair<Integer, Integer>> entry : productionMapBack.entrySet()) {
            int index = entry.getKey();
            int fragment = entry.getValue().first();
            int fragmentIndex = entry.getValue().second();

            if (fragment == 0) {
                productionLengths[index] = hostFragment.fullSpec.pr.getRHSLength(fragmentIndex);
            } else {
                productionLengths[index] = extensionFragments.get(fragment - 1).extensionMappingSpec.pr.getRHSLength(fragmentIndex);
            }
        }
    }

    private void makeProductionLHSs() {
        productionLHSs = new int[totalProductionCount];

        for (Map.Entry<Integer, Pair<Integer, Integer>> entry : productionMapBack.entrySet()) {
            int index = entry.getKey();
            int fragment = entry.getValue().first();
            int fragmentIndex = entry.getValue().second();

            if (fragment == 0) {
                productionLHSs[index] = hostFragment.fullSpec.pr.getLHS(fragmentIndex);
            } else {
                ExtensionMappingSpec spec = extensionFragments.get(fragment - 1).extensionMappingSpec;
                int encodedLHS = spec.untranslateAndOffsetComposedSymbol(spec.pr.getLHS(fragmentIndex));
                productionLHSs[index] = encodedLHS < 0 ? spec.decodeAndTableOffsetExtensionIndex(encodedLHS) : encodedLHS;
            }
        }
    }

    private void writeHashes(PrintStream out) throws IOException {
        ByteArrayOutputStream stringOut = new ByteArrayOutputStream();
        for (ObjectToHash obj: objectsToHash) {
            writeHash(obj.obj, obj.name, stringOut, out);
        }
    }

    private void writeHash(Object obj, String prefix, ByteArrayOutputStream stringOut, PrintStream out) throws IOException {
        stringOut.reset();
        ObjectOutputStream outp = new ObjectOutputStream(stringOut);
        outp.writeObject(obj);
        out.println("  public static final byte[] " + prefix + "Hash = " + ByteArrayEncoder.class.getName() + ".literalToByteArray\n(new String[]{ " + ByteArrayEncoder.byteArrayToLiteral(16,stringOut.toByteArray()) + "});\n");
    }

    private void writeStaticMemberInitializations(PrintStream out) {
        out.println("  public static void initArrays() throws " + IOException.class.getName() + "," + ClassNotFoundException.class.getName() + " {");
        for (ObjectToHash obj: objectsToHash) {
            out.println("    " + obj.name + " = (" + obj.type + ") " + ByteArrayEncoder.class.getName() + ".readHash(" + obj.name + "Hash);");
        }
        out.println("  }");

        out.println("  static {");
        out.println("    try { initArrays(); }");
        out.println("    catch(" + IOException.class.getName() + " ex) { ex.printStackTrace(); System.exit(1); }");
        out.println("    catch(" + ClassNotFoundException.class.getName() + " ex) { ex.printStackTrace(); System.exit(1); }");
        initializeDisambiguationGroups(out);
        out.println("  }");
    }

    private void initializeDisambiguationGroups(PrintStream out) {
        out.println("    disambiguationGroups = new " + BitSet.class.getName() + "[" + totalDisambiguationFunctionCount + "];");
        for (Map.Entry<Integer, Pair<Integer, Integer>> entry : disambiguationFunctionMapBack.entrySet()) {
            int df = entry.getKey();
            int fragment = entry.getValue().first();
            int fragmentIndex = entry.getValue().second();

            BitSet members = fragment == 0 ? hostFragment.fullSpec.df.getMembers(fragmentIndex) : extensionFragments.get(fragment - 1).extensionMappingSpec.df.getMembers(fragmentIndex);

            int terminalCount = fragment == 0 ? hostTerminalLength : extTerminalLengths[fragment - 1];
            out.print("    disambiguationGroups[" + df + "] = newBitVec(" + terminalCount);
            for (int t = members.nextSetBit(0); t >= 0; t = members.nextSetBit(t + 1)) {
                out.print(", " + t);
            }
            out.print(");\n");
        }
    }

    // element is unoffset index
    private String generateVariableName(int fragment, int element) {
        PSSymbolTable symbolTable = fragment == 0 ? hostFragment.symbolTable : extensionFragments.get(fragment - 1).extensionMappingSpec.extensionSymbolTable;
        if (symbolTable.get(element).getType() == CopperElementType.SPECIAL) {
            ParserSpec spec = hostFragment.fullSpec;
            if (element == spec.getEOFTerminal()) {
                return "EOF";
            } else if (element == spec.getStartNonterminal()) {
                return "START";
            } else if (element == spec.getStartProduction()) {
                return "STARTP";
            } else {
                return null;
            }
        } else {
            return symbolTable.get(element).getName().toString();
        }
        // TODO confirm that no need to check isUnitary or that sort of thing
        // need to differentiate non-unitary with <grammar>$...
    }
}
