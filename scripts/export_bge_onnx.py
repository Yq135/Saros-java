#!/usr/bin/env python3
"""一次性导出 bge-small-zh-v1.5 → ONNX + vocab.txt + 校准样本（阶段三 J1 前置，PLAN.md §5.1）。

用法（conda saros 环境，需 sentence-transformers；国内网络自动走 hf-mirror）：
    python3 scripts/export_bge_onnx.py [模型名或本地路径]

输出：
    data/models/bge-small-zh-v1.5/model.onnx        # 输入 input_ids/attention_mask(int64 [B,S]) → 输出 512 维向量(float32)
                                                     # 均值池化(attention mask) + L2 归一化已固化进图，
                                                     # 与阶段二 encode(normalize_embeddings=True) 逐位一致
    data/models/bge-small-zh-v1.5/vocab.txt          # BERT 词表（行号=token id，Java BertTokenizer 用）
    data/models/bge-small-zh-v1.5/calibration.json   # 校准样本（原文/token_ids/向量）
    src/test/resources/embedding/calibration.json    # 同上（入库，Java 对齐测试断言用）
"""
import json
import os
import sys
from pathlib import Path

import torch
from sentence_transformers import SentenceTransformer

# 与阶段二 embeddings.py 保持一致的 BGE 查询前缀（查询侧专用，文档侧不加）
QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章："

MODEL_NAME = sys.argv[1] if len(sys.argv) > 1 else "BAAI/bge-small-zh-v1.5"
REPO_ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = REPO_ROOT / "data" / "models" / "bge-small-zh-v1.5"
CALIB_COMMIT = REPO_ROOT / "src" / "test" / "resources" / "embedding" / "calibration.json"

# 国内网络默认走 HF 镜像（可用 HF_ENDPOINT 覆盖）
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")


def main() -> None:
    print(f"加载模型：{MODEL_NAME}（HF_ENDPOINT={os.environ.get('HF_ENDPOINT')}）")
    # 强制 CPU：ONNX 目标即 CPU 推理，且与阶段二（CPU）及校准向量基准一致（MPS 有微小数值差异）
    # attn_implementation=eager：transformers 5.x 默认 SDPA 路径经旧版 TorchScript 导出器
    # 符号化后与 eager 前向不一致（实测余弦仅 0.925）；eager 即标准 softmax 注意力，
    # 数学等价、导出干净（sdpa 与 eager 数值差 < 1e-6，与阶段二存量向量兼容）。
    # 注意：必须在加载时指定（5.x 注意力实现于实例化时固化，事后改 config 无效）
    model = SentenceTransformer(MODEL_NAME, device="cpu", model_kwargs={"attn_implementation": "eager"})
    model.eval()

    max_seq = int(model.max_seq_length)
    dim = int(model.get_sentence_embedding_dimension())
    tokenizer = model.tokenizer
    print(f"max_seq_length={max_seq} dim={dim}")

    # do_lower_case 决定 Java 侧 BertTokenizer 的 optLowerCase（写入校准元数据）
    tok_cfg = getattr(tokenizer, "init_kwargs", {}) or {}
    do_lower = bool(tok_cfg.get("do_lower_case", False))
    print(f"do_lower_case={do_lower}")

    # ---- ONNX 导出：池化 + L2 归一化固化进图 ----
    base = model[0]
    if hasattr(base, "auto_model"):  # ST 5.x：Transformer.auto_model 是底层 HF 模型
        base = base.auto_model

    # 池化方式以模型的 ST 配置为准（bge-small-zh-v1.5 为 CLS 池化：pooling_mode_cls_token=True，
    # 而非均值池化——实测均值 vs CLS 余弦仅 0.925，必须对齐，否则与阶段二存量向量不兼容）
    pooling_mode = str(getattr(model[1], "pooling_mode", "cls")).lower()
    print(f"pooling_mode={pooling_mode}")

    class ExportWrapper(torch.nn.Module):
        def forward(self, input_ids, attention_mask):
            hidden = self.base(input_ids=input_ids, attention_mask=attention_mask).last_hidden_state
            if self.pooling_mode == "cls":
                pooled = hidden[:, 0, :]  # 取 [CLS] 位置向量
            else:  # mean：带 mask 的均值池化
                mask = attention_mask.unsqueeze(-1).float()  # [B,S,1]
                pooled = (hidden * mask).sum(dim=1) / mask.sum(dim=1).clamp(min=1e-9)
            return torch.nn.functional.normalize(pooled, p=2, dim=1)

    wrapped = ExportWrapper()
    wrapped.base = base
    wrapped.pooling_mode = pooling_mode

    # ---- 校准样本（文档 10 条 + 查询 2 条；覆盖长文本截断、中英混排、标点） ----
    doc_texts = [
        "PostgreSQL 的 pgvector 扩展支持对向量进行余弦距离检索，常用于 RAG 知识库场景。",
        "梯度下降是机器学习中最基础的优化算法，通过沿负梯度方向迭代更新参数来最小化损失函数。",
        "Java 21 的虚拟线程（Virtual Threads）由 JVM 调度，千万级并发不再是内存瓶颈。",
        "BGE 系列嵌入模型（bge-small-zh-v1.5）为中文检索做了优化，查询侧需要加特定前缀。",
        "HTTP Range 请求允许客户端只获取文件的某一段字节，视频拖动播放依赖它。",
        "混合检索 = 语义相似度 + 关键词重叠 + 标签命中 加权打分，比单一向量检索更可靠。",
        "Spring Boot 的 SseEmitter 可以向前端推送流式事件，适合 ChatGPT 式的逐字输出。",
        "B 站视频字幕有三种来源：官方 CC 字幕、AI 字幕（ai-zh）、无字幕时走 ASR 音频转写。",
        "「沉淀即永恒」：联网获取的新知识要与历史笔记碰撞复用，形成私有知识资产。",
        "DuckDuckGo 提供免费的 HTML 搜索端点，配合 jsoup 解析即可实现零成本联网搜索。"
        + "这一条用于验证超过最大序列长度的截断行为。" * 40,
        "machine learning, embedding, vector search: 中英文混合 tokenization 验证。",
    ]
    query_texts = [
        "什么是虚拟线程？它解决了什么问题？",
        "向量检索和关键词检索各自的优缺点是什么？",
    ]

    def sample(text: str, with_prefix: bool) -> dict:
        src = (QUERY_PREFIX + text) if with_prefix else text
        # 与 ST encode 内部完全一致的 tokenize（含 max_seq_length 截断）
        tok = tokenizer(src, truncation=True, max_length=max_seq, padding=False)
        vec = model.encode([src], normalize_embeddings=True)[0]
        return {
            "text": text,
            "token_ids": tok["input_ids"],
            "tokens": tokenizer.convert_ids_to_tokens(tok["input_ids"]),
            "embedding": [float(x) for x in vec.tolist()],
        }

    doc_samples = [sample(t, with_prefix=False) for t in doc_texts]
    query_samples = [sample(t, with_prefix=True) for t in query_texts]
    print(f"校准样本：文档 {len(doc_samples)} 条 + 查询 {len(query_samples)} 条")

    # ---- ONNX 导出（用第一条文档样本做 dummy input，动态 batch/seq） ----
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    # 清理旧导出残留的外部权重文件（Dynamo 导出器会产生 .data；旧版导出器为自包含单文件）
    for stale in OUT_DIR.glob("model.onnx.data"):
        stale.unlink()
    ids = torch.tensor([doc_samples[0]["token_ids"]], dtype=torch.long)
    mask = torch.ones_like(ids)
    onnx_path = OUT_DIR / "model.onnx"
    torch.onnx.export(
        wrapped,
        (ids, mask),
        str(onnx_path),
        input_names=["input_ids", "attention_mask"],
        output_names=["embedding"],
        dynamic_axes={
            "input_ids": {0: "batch", 1: "seq"},
            "attention_mask": {0: "batch", 1: "seq"},
            "embedding": {0: "batch"},
        },
        opset_version=17,
        # 必须用旧版 TorchScript 导出器：torch 2.13 默认的 Dynamo 导出器会对本模型
        # 产出两个同名 "embedding" 的节点输出（SSA 违规），ONNX Runtime 拒绝加载
        dynamo=False,
    )
    print(f"ONNX 已导出：{onnx_path}")

    # ---- vocab.txt（行号 = token id） ----
    vocab = sorted(tokenizer.get_vocab().items(), key=lambda kv: kv[1])
    vocab_path = OUT_DIR / "vocab.txt"
    vocab_path.write_text("\n".join(t for t, _ in vocab) + "\n", encoding="utf-8")
    print(f"词表已导出：{vocab_path}（{len(vocab)} 词）")

    # ---- 校准 JSON（两份：模型目录 + 测试资源入库） ----
    payload = {
        "model": MODEL_NAME,
        "max_seq_length": max_seq,
        "dim": dim,
        "do_lower_case": do_lower,
        "query_prefix": QUERY_PREFIX,
        "doc_samples": doc_samples,
        "query_samples": query_samples,
    }
    dump = json.dumps(payload, ensure_ascii=False, indent=2)
    (OUT_DIR / "calibration.json").write_text(dump, encoding="utf-8")
    CALIB_COMMIT.parent.mkdir(parents=True, exist_ok=True)
    CALIB_COMMIT.write_text(dump, encoding="utf-8")
    print(f"校准样本已写入：{OUT_DIR / 'calibration.json'} 与 {CALIB_COMMIT}")

    # ---- 自检：ONNX 推理 vs ST 输出（同一模型，容差应 < 1e-4） ----
    try:
        import onnxruntime as ort
    except ImportError:
        print("跳过导出自检（saros 环境无 onnxruntime，不影响产物；Java 侧对齐测试会兜底校验）")
        return
    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    got = sess.run(["embedding"], {
        "input_ids": ids.numpy(), "attention_mask": mask.numpy(),
    })[0][0]
    ref = doc_samples[0]["embedding"]
    import numpy as np
    cos = float(np.dot(got, ref)) / (float(np.linalg.norm(got)) * float(np.linalg.norm(ref)))
    print(f"导出自检：ONNX vs ST 余弦 = {cos:.6f}（期望 ≈1.000000）")


if __name__ == "__main__":
    main()
