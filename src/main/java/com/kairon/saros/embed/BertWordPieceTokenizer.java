package com.kairon.saros.embed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BERT WordPiece 分词器——对齐 HuggingFace BertTokenizer（Python 慢速版）语义，
 * 词表来自 scripts/export_bge_onnx.py 导出的 vocab.txt（行号 = token id）。
 *
 * <p>实现要点（与 transformers 5.x 实际行为一致，EmbeddingAlignmentTest 逐 token 断言兜底）：
 * <ol>
 *   <li>BasicTokenizer：空白切分 → CJK 逐字切分 → 标点切分（ASCII 标点 + Unicode 标点/符号类别）。
 *       不 lowercase、不去重音——实测本模型 do_lower_case=False，且 "Café" → [UNK] 证明未去重音</li>
 *   <li>WordPiece：**整词小写后**命中直接用（transformers 5.x Rust 后端在 WordPiece 阶段无条件小写，
 *       实测 "PostgreSQL" → post/##g/##res/##ql、"HTTPServer" → https/##er/##ver）；
 *       否则贪心最长匹配 "##" 子词；超 100 字符或完全未命中 → [UNK]</li>
 *   <li>组装：[CLS] + tokens + [SEP]；超 maxLength 时保留前 maxLength-1 个 + [SEP]</li>
 * </ol>
 */
public class BertWordPieceTokenizer {

    private static final int MAX_INPUT_CHARS_PER_WORD = 100;

    private final Map<String, Integer> vocab;
    private final int maxLength;
    private final int clsId;
    private final int sepId;
    private final int unkId;

    public BertWordPieceTokenizer(Path vocabFile, int maxLength, boolean doLowerCase) {
        // doLowerCase 仅作语义记录：实测 WordPiece 阶段无条件小写（与模型配置无关），见类注释
        this.maxLength = maxLength;
        List<String> tokens;
        try {
            tokens = Files.readAllLines(vocabFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取词表失败：" + vocabFile, e);
        }
        this.vocab = new HashMap<>(tokens.size() * 2);
        for (int i = 0; i < tokens.size(); i++) {
            vocab.put(tokens.get(i), i);
        }
        this.clsId = vocab.getOrDefault("[CLS]", -1);
        this.sepId = vocab.getOrDefault("[SEP]", -1);
        this.unkId = vocab.getOrDefault("[UNK]", 0);
    }

    /** 完整编码：[CLS] + wordpieces + [SEP]，按 maxLength 截断。 */
    public long[] tokenize(String text) {
        List<String> pieces = new ArrayList<>();
        pieces.add("[CLS]");
        for (String token : basicTokenize(text)) {
            pieces.addAll(wordPiece(token));
        }
        pieces.add("[SEP]");
        if (pieces.size() > maxLength) {
            pieces = pieces.subList(0, maxLength - 1);
            pieces.add("[SEP]");
        }
        long[] ids = new long[pieces.size()];
        for (int i = 0; i < pieces.size(); i++) {
            ids[i] = vocab.getOrDefault(pieces.get(i), unkId);
        }
        return ids;
    }

    /** BasicTokenizer：空白切分 → CJK 逐字切分 → 标点切分（不 lowercase、不去重音）。 */
    private List<String> basicTokenize(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String token : text.trim().split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            splitCjkAndPunctuation(token, out);
        }
        return out;
    }

    /** HF _tokenize_chinese_chars + _run_split_on_punc：CJK 逐字、标点独立成 token。 */
    private void splitCjkAndPunctuation(String token, List<String> out) {
        int start = 0;
        int i = 0;
        while (i < token.length()) {
            int cp = token.codePointAt(i);
            if (isChineseChar(cp) || isPunctuation(cp)) {
                if (start < i) {
                    out.add(token.substring(start, i));
                }
                out.add(new String(Character.toChars(cp)));
                start = i + Character.charCount(cp);
            }
            i += Character.charCount(cp);
        }
        if (start < token.length()) {
            out.add(token.substring(start));
        }
    }

    /** WordPiece：整词小写后命中 → 贪心最长匹配 ## 子词 → [UNK]（Rust 后端强制小写，见类注释）。 */
    private List<String> wordPiece(String token) {
        token = token.toLowerCase(Locale.ROOT);
        if (vocab.containsKey(token)) {
            return List.of(token);
        }
        List<String> pieces = new ArrayList<>();
        if (token.codePointCount(0, token.length()) > MAX_INPUT_CHARS_PER_WORD) {
            pieces.add("[UNK]");
            return pieces;
        }
        int start = 0;
        while (start < token.length()) {
            int end = token.length();
            String candidate = null;
            while (start < end) {
                String sub = token.substring(start, end);
                String key = pieces.isEmpty() ? sub : "##" + sub;
                if (vocab.containsKey(key)) {
                    candidate = key;
                    break;
                }
                end--;
            }
            if (candidate == null) {
                pieces.add("[UNK]");
                break;
            }
            pieces.add(candidate);
            start = end;
        }
        return pieces;
    }

    /** HF is_chinese_char：CJK 统一表意文字等区间。 */
    private static boolean isChineseChar(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x20000 && cp <= 0x2A6DF)
                || (cp >= 0x2A700 && cp <= 0x2B73F)
                || (cp >= 0x2B740 && cp <= 0x2B81F)
                || (cp >= 0x2B820 && cp <= 0x2CEAF)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0x2F800 && cp <= 0x2FA1F);
    }

    /** HF _is_punctuation：ASCII 标点区间 + Unicode 标点/符号类别。 */
    private static boolean isPunctuation(int cp) {
        if ((cp >= 33 && cp <= 47) || (cp >= 58 && cp <= 64)
                || (cp >= 91 && cp <= 96) || (cp >= 123 && cp <= 126)) {
            return true;
        }
        int type = Character.getType(cp);
        return type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.CONNECTOR_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.MATH_SYMBOL
                || type == Character.CURRENCY_SYMBOL
                || type == Character.MODIFIER_SYMBOL
                || type == Character.OTHER_SYMBOL;
    }
}
