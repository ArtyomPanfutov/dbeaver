/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2019 Serge Rider (serge@jkiss.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ui.editors.sql.syntax;

import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.TextAttribute;
import org.eclipse.jface.text.rules.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.themes.ITheme;
import org.eclipse.ui.themes.IThemeManager;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.sql.SQLConstants;
import org.jkiss.dbeaver.model.sql.SQLDialect;
import org.jkiss.dbeaver.model.sql.SQLSyntaxManager;
import org.jkiss.dbeaver.model.sql.parser.SQLWordDetector;
import org.jkiss.dbeaver.model.sql.registry.SQLCommandHandlerDescriptor;
import org.jkiss.dbeaver.model.sql.registry.SQLCommandsRegistry;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.runtime.sql.SQLRuleProvider;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorBase;
import org.jkiss.dbeaver.ui.editors.sql.SQLPreferenceConstants;
import org.jkiss.dbeaver.ui.editors.sql.syntax.rules.*;
import org.jkiss.dbeaver.ui.editors.sql.syntax.tokens.*;
import org.jkiss.dbeaver.ui.editors.text.TextWhiteSpaceDetector;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.Pair;

import java.util.*;

/**
 * SQLSyntaxManager.
 * <p/>
 * Contains information about some concrete datasource underlying database syntax.
 * Support runtime change of datasource (reloads syntax information)
 */
public class SQLRuleManager extends RuleBasedScanner {

    @NotNull
    private final IThemeManager themeManager;
    @NotNull
    private SQLSyntaxManager syntaxManager;
    @NotNull
    private TreeMap<Integer, SQLScriptPosition> positions = new TreeMap<>();
    private Set<SQLScriptPosition> addedPositions = new HashSet<>();
    private Set<SQLScriptPosition> removedPositions = new HashSet<>();

    private boolean evalMode;

    public SQLRuleManager(@NotNull SQLSyntaxManager syntaxManager)
    {
        this.syntaxManager = syntaxManager;
        this.themeManager = PlatformUI.getWorkbench().getThemeManager();
    }

    public boolean isEvalMode() {
        return evalMode;
    }

    public void startEval() {
        this.evalMode = true;
    }

    public void endEval() {
        this.evalMode = false;
        if (fRules != null) {
            for (IRule rule : fRules) {
                if (rule instanceof SQLDelimiterRule) {
                    ((SQLDelimiterRule) rule).changeDelimiter(null);
                }
            }
        }
    }

    public void dispose()
    {
    }

    @NotNull
    public Collection<? extends Position> getPositions(int offset, int length)
    {
        return positions.subMap(offset, offset + length).values();
    }

    @NotNull
    public synchronized Set<SQLScriptPosition> getRemovedPositions(boolean clear)
    {
        Set<SQLScriptPosition> posList = removedPositions;
        if (clear) {
            removedPositions = new HashSet<>();
        }
        return posList;
    }

    @NotNull
    public synchronized Set<SQLScriptPosition> getAddedPositions(boolean clear)
    {
        Set<SQLScriptPosition> posList = addedPositions;
        if (clear) {
            addedPositions = new HashSet<>();
        }
        return posList;
    }

    public void refreshRules(@Nullable DBPDataSource dataSource, @Nullable IEditorInput editorInput)
    {
        SQLDialect dialect = syntaxManager.getDialect();
        SQLRuleProvider ruleProvider = GeneralUtils.adapt(dialect, SQLRuleProvider.class);

        boolean minimalRules = SQLEditorBase.isBigScript(editorInput);

        boolean boldKeywords = dataSource == null ?
            DBWorkbench.getPlatform().getPreferenceStore().getBoolean(SQLPreferenceConstants.SQL_FORMAT_BOLD_KEYWORDS) :
            dataSource.getContainer().getPreferenceStore().getBoolean(SQLPreferenceConstants.SQL_FORMAT_BOLD_KEYWORDS);
        int keywordStyle = boldKeywords ? SWT.BOLD : SWT.NORMAL;

        /*final Color backgroundColor = null;unassigned || dataSource != null ?
            getColor(SQLConstants.CONFIG_COLOR_BACKGROUND, SWT.COLOR_WHITE) :
            getColor(SQLConstants.CONFIG_COLOR_DISABLED, SWT.COLOR_WIDGET_LIGHT_SHADOW);*/
        final IToken keywordToken = new Token(
            new TextAttribute(getColor(SQLConstants.CONFIG_COLOR_KEYWORD), null, keywordStyle));
        final IToken typeToken = new Token(
            new TextAttribute(getColor(SQLConstants.CONFIG_COLOR_DATATYPE), null, keywordStyle));
        final IToken stringToken = new Token(
            new TextAttribute(getColor(SQLConstants.CONFIG_COLOR_STRING), null, SWT.NORMAL));
        final IToken quotedToken = new Token(
            new TextAttribute(getColor(SQLConstants.CONFIG_COLOR_DATATYPE), null, SWT.NORMAL));
        final IToken numberToken = new Token(
            new TextAttribute(getColor(SQLConstants.CONFIG_COLOR_NUMBER), null, SWT.NORMAL));
        final IToken commentToken = new SQLCommentToken(
            new TextAttribute(getColor(SQLConstants.CONFIG_COLOR_COMMENT), null, SWT.NORMAL));
        final SQLDelimiterToken delimiterToken = new SQLDelimiterToken(
            new TextAttribute(getColor(SQLConstants.CONFIG_COLOR_DELIMITER, SWT.COLOR_RED), null, SWT.NORMAL));
        final SQLParameterToken parameterToken = new SQLParameterToken(
            new TextAttribute(getColor(SQLConstants.CONFIG_COLOR_PARAMETER, SWT.COLOR_DARK_BLUE), null, keywordStyle));
        final SQLVariableToken variableToken = new SQLVariableToken(
            new TextAttribute(getColor(SQLConstants.CONFIG_COLOR_PARAMETER, SWT.COLOR_DARK_BLUE), null, keywordStyle));
        final IToken otherToken = new Token(
            new TextAttribute(getColor(SQLConstants.CONFIG_COLOR_TEXT), null, SWT.NORMAL));
        final SQLBlockHeaderToken blockHeaderToken = new SQLBlockHeaderToken(
            new TextAttribute(getColor(SQLConstants.CONFIG_COLOR_KEYWORD), null, keywordStyle));
        final SQLBlockBeginToken blockBeginToken = new SQLBlockBeginToken(
            new TextAttribute(getColor(SQLConstants.CONFIG_COLOR_KEYWORD), null, keywordStyle));
        final SQLBlockEndToken blockEndToken = new SQLBlockEndToken(
            new TextAttribute(getColor(SQLConstants.CONFIG_COLOR_KEYWORD), null, keywordStyle));

        setDefaultReturnToken(otherToken);
        List<IRule> rules = new ArrayList<>();

        if (ruleProvider != null) {
            ruleProvider.extendRules(rules, SQLRuleProvider.RulePosition.INITIAL);
        }

        // Add rule for single-line comments.
        for (String lineComment : dialect.getSingleLineComments()) {
            if (lineComment.startsWith("^")) {
                rules.add(new LineCommentRule(lineComment, commentToken)); //$NON-NLS-1$
            } else {
                rules.add(new EndOfLineRule(lineComment, commentToken)); //$NON-NLS-1$
            }
        }

        if (ruleProvider != null) {
            ruleProvider.extendRules(rules, SQLRuleProvider.RulePosition.CONTROL);
        }

        if (!minimalRules) {
            final SQLControlToken controlToken = new SQLControlToken(
                    new TextAttribute(getColor(SQLConstants.CONFIG_COLOR_COMMAND), null, keywordStyle));

            String commandPrefix = syntaxManager.getControlCommandPrefix();

            // Control rules
            for (SQLCommandHandlerDescriptor controlCommand : SQLCommandsRegistry.getInstance().getCommandHandlers()) {
                rules.add(new SQLCommandRule(commandPrefix, controlCommand, controlToken)); //$NON-NLS-1$
            }
        }
        {
            if (!minimalRules && syntaxManager.isVariablesEnabled()) {
                // Variable rule
                rules.add(new SQLVariableRule(parameterToken));
            }
        }

        if (!minimalRules) {
            // Add rules for delimited identifiers and string literals.
            char escapeChar = syntaxManager.getEscapeChar();
            String[][] identifierQuoteStrings = syntaxManager.getIdentifierQuoteStrings();
            String[][] stringQuoteStrings = syntaxManager.getStringQuoteStrings();

            boolean hasDoubleQuoteRule = false;
            if (!ArrayUtils.isEmpty(identifierQuoteStrings)) {
                for (String[] quotes : identifierQuoteStrings) {
                    rules.add(new SingleLineRule(quotes[0], quotes[1], quotedToken, escapeChar));
                    if (quotes[1].equals(SQLConstants.STR_QUOTE_DOUBLE) && quotes[0].equals(quotes[1])) {
                        hasDoubleQuoteRule = true;
                    }
                }
            }
            if (!ArrayUtils.isEmpty(stringQuoteStrings)) {
                for (String[] quotes : stringQuoteStrings) {
                    rules.add(new MultiLineRule(quotes[0], quotes[1], stringToken, escapeChar));
                }
            }
            if (!hasDoubleQuoteRule) {
                rules.add(new MultiLineRule(SQLConstants.STR_QUOTE_DOUBLE, SQLConstants.STR_QUOTE_DOUBLE, quotedToken, escapeChar));
            }
        }
        if (ruleProvider != null) {
            ruleProvider.extendRules(rules, SQLRuleProvider.RulePosition.QUOTES);
        }

        // Add rules for multi-line comments
        Pair<String, String> multiLineComments = dialect.getMultiLineComments();
        if (multiLineComments != null) {
            rules.add(new MultiLineRule(multiLineComments.getFirst(), multiLineComments.getSecond(), commentToken, (char) 0, true));
        }

        if (!minimalRules) {
            // Add generic whitespace rule.
            rules.add(new WhitespaceRule(new TextWhiteSpaceDetector()));

            // Add numeric rule
            rules.add(new NumberRule(numberToken));
        }

        SQLDelimiterRule delimRule = new SQLDelimiterRule(syntaxManager.getStatementDelimiters(), delimiterToken);
        rules.add(delimRule);

        {
            // Delimiter redefine
            String delimRedefine = dialect.getScriptDelimiterRedefiner();
            if (!CommonUtils.isEmpty(delimRedefine)) {
                final SQLSetDelimiterToken setDelimiterToken = new SQLSetDelimiterToken(
                    new TextAttribute(getColor(SQLConstants.CONFIG_COLOR_COMMAND), null, keywordStyle));

                rules.add(new SQLDelimiterSetRule(delimRedefine, setDelimiterToken, delimRule));
            }
        }

        if (ruleProvider != null) {
            ruleProvider.extendRules(rules, SQLRuleProvider.RulePosition.KEYWORDS);
        }

        if (!minimalRules) {

            // Add word rule for keywords, types, and constants.
            SQLWordRule wordRule = new SQLWordRule(delimRule, otherToken);
            for (String reservedWord : dialect.getReservedWords()) {
                wordRule.addWord(reservedWord, keywordToken);
            }
            if (dataSource != null) {
                for (String function : dialect.getFunctions(dataSource)) {
                    wordRule.addWord(function, typeToken);
                }
                for (String type : dialect.getDataTypes(dataSource)) {
                    wordRule.addWord(type, typeToken);
                }
            }
            final String[] blockHeaderStrings = dialect.getBlockHeaderStrings();
            if (!ArrayUtils.isEmpty(blockHeaderStrings)) {
                for (String bhs : blockHeaderStrings) {
                    wordRule.addWord(bhs, blockHeaderToken);
                }
            }
            String[][] blockBounds = dialect.getBlockBoundStrings();
            if (blockBounds != null) {
                for (String[] block : blockBounds) {
                    if (block.length != 2) {
                        continue;
                    }
                    wordRule.addWord(block[0], blockBeginToken);
                    wordRule.addWord(block[1], blockEndToken);
                }
            }
            rules.add(wordRule);

            // Parameter rule
            for (String npPrefix : syntaxManager.getNamedParameterPrefixes()) {
                rules.add(new SQLParameterRule(syntaxManager, parameterToken, npPrefix));
            }
        }

        IRule[] result = new IRule[rules.size()];
        rules.toArray(result);
        setRules(result);
    }

    public Color getColor(String colorKey)
    {
        return getColor(colorKey, SWT.COLOR_BLACK);
    }

    public Color getColor(String colorKey, int colorDefault)
    {
        ITheme currentTheme = themeManager.getCurrentTheme();
        Color color = currentTheme.getColorRegistry().get(colorKey);
        if (color == null) {
            color = Display.getDefault().getSystemColor(colorDefault);
        }
        return color;
    }

    private static IWordDetector getWordOrSymbolDetector(String word) {
        if (Character.isLetterOrDigit(word.charAt(0))) {
            return new WordDetectorAdapter(new SQLWordDetector());
        } else {
            // Default delim rule
            return new SymbolSequenceDetector(word);
        }
    }

    private static class WordDetectorAdapter implements IWordDetector {
        private final SQLWordDetector wordDetector;

        private WordDetectorAdapter(SQLWordDetector wordDetector) {
            this.wordDetector = wordDetector;
        }

        @Override
        public boolean isWordStart(char c) {
            return wordDetector.isWordStart(c);
        }

        @Override
        public boolean isWordPart(char c) {
            return wordDetector.isWordPart(c);
        }
    }

    private static class SymbolSequenceDetector implements IWordDetector {
        private final String delimiter;

        public SymbolSequenceDetector(String delimiter) {
            this.delimiter = delimiter;
        }

        @Override
        public boolean isWordStart(char c) {
            return delimiter.charAt(0) == c;
        }

        @Override
        public boolean isWordPart(char c) {
            return delimiter.indexOf(c) != -1;
        }
    }

}
