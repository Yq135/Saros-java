-- ==========================================
-- Saros 数据库初始化脚本（v0.6 / 2026-08-18）
-- ==========================================

-- ==========================================
-- 1. 数据库与扩展初始化
-- ==========================================
-- 如果 saros_db 已存在，请跳过此行
-- CREATE DATABASE saros_db;


-- 启用 pgvector 扩展 (用于向量检索)
-- 需要先安装 pgvector 插件
CREATE EXTENSION IF NOT EXISTS vector;

-- ==========================================
-- 2. 通用触发器：updated_at 自动更新
-- ==========================================
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ==========================================
-- 3. 基础表：用户
-- ==========================================
-- 当前为单用户、无登录；user_id 由应用层约定（默认 1）
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE users IS '用户表（单用户，无登录；user_id 应用层约定默认 1）';
COMMENT ON COLUMN users.id IS '用户ID';
COMMENT ON COLUMN users.username IS '用户名';

-- ==========================================
-- 4. 模块四：知识沉淀（正式标签的唯一宿主）
-- ==========================================
-- 手打知识表
CREATE TABLE manual_knowledge (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    content TEXT NOT NULL,
    mastery_level INT DEFAULT 0 CHECK (mastery_level BETWEEN 0 AND 5),
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE manual_knowledge IS '手打知识沉淀表（正式标签的唯一宿主，向量存 embeddings 表）';
COMMENT ON COLUMN manual_knowledge.id IS '主键ID';
COMMENT ON COLUMN manual_knowledge.user_id IS '创建人ID';
COMMENT ON COLUMN manual_knowledge.content IS '知识点内容（纯文本/Markdown）';
COMMENT ON COLUMN manual_knowledge.mastery_level IS '掌握程度 (0-5)';

CREATE TRIGGER trg_manual_knowledge_updated_at BEFORE UPDATE ON manual_knowledge
FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- 正式标签表（仅关联手打知识；问答/网页/视频的 AI 标签为 suggested_tags 文本，不入本表）
CREATE TABLE tags (
    id BIGSERIAL PRIMARY KEY,
    manual_knowledge_id BIGINT NOT NULL REFERENCES manual_knowledge(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (manual_knowledge_id, name)   -- 同一笔记下标签不重复
);

COMMENT ON TABLE tags IS '正式标签表（仅属于手打笔记；方案B：其他模块标签仅为推荐文本）';
COMMENT ON COLUMN tags.id IS '标签ID';
COMMENT ON COLUMN tags.manual_knowledge_id IS '所属笔记ID（级联删除）';
COMMENT ON COLUMN tags.name IS '标签名称';

-- 索引：UNIQUE(manual_knowledge_id, name) 已覆盖按笔记查标签
CREATE INDEX idx_tags_name ON tags(name); -- 用于自动补全搜索

-- ==========================================
-- 5. 模块一：联网问答（多轮对话）
-- ==========================================
-- v0.6 迁移说明：原 qa_sessions 表已更名 qa_messages 并新增 qa_conversations。
-- 旧表无数据，直接删除即可：
--   DROP TABLE IF EXISTS qa_sessions CASCADE;

-- 会话表（一次围绕主题的多轮探讨；删除会话级联删除全部轮次）
CREATE TABLE qa_conversations (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    title VARCHAR(255) NOT NULL DEFAULT '',  -- 标题取首问截断（非 LLM 生成）
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE qa_conversations IS '问答会话表（多轮对话容器，标题取首问截断）';
COMMENT ON COLUMN qa_conversations.user_id IS '创建人ID';
COMMENT ON COLUMN qa_conversations.title IS '会话标题（首问截断，非 LLM 生成）';

CREATE TRIGGER trg_qa_conversations_updated_at BEFORE UPDATE ON qa_conversations
FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- 轮次表（一问一答一条记录；仅检索手打笔记，对话内容不入沉淀）
CREATE TABLE qa_messages (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES qa_conversations(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id),
    question TEXT NOT NULL,
    answer TEXT,
    search_sources JSONB,
    referenced_knowledge_ids BIGINT[],  -- 引用的 manual_knowledge.id（仅检索手打笔记）
    suggested_tags TEXT[],              -- AI推荐的标签（非正式；仅会话首轮生成）
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE qa_messages IS '问答轮次表（会话内一问一答；删除会话级联删除）';
COMMENT ON COLUMN qa_messages.conversation_id IS '所属会话ID（级联删除）';
COMMENT ON COLUMN qa_messages.user_id IS '创建人ID';
COMMENT ON COLUMN qa_messages.question IS '用户问题';
COMMENT ON COLUMN qa_messages.answer IS '系统回答';
COMMENT ON COLUMN qa_messages.search_sources IS '本轮搜索源';
COMMENT ON COLUMN qa_messages.referenced_knowledge_ids IS '本轮引用的 manual_knowledge.id（沉淀知识）';
COMMENT ON COLUMN qa_messages.suggested_tags IS 'AI推荐的标签（文本数组，仅会话首轮生成）';

-- 按会话取轮次（时间升序展示）
CREATE INDEX idx_qa_messages_conversation ON qa_messages(conversation_id, created_at);

-- ==========================================
-- 6. 模块二：网页出题
-- ==========================================
CREATE TABLE web_articles (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    url VARCHAR(1024) NOT NULL UNIQUE,
    title VARCHAR(255),
    content TEXT NOT NULL,
    suggested_tags TEXT[],              -- AI推荐的标签（非正式）
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE web_articles IS '网页文章表（题目存 webpage_questions 子表）';
COMMENT ON COLUMN web_articles.user_id IS '创建人ID';
COMMENT ON COLUMN web_articles.url IS '网页地址';
COMMENT ON COLUMN web_articles.title IS '网页标题';
COMMENT ON COLUMN web_articles.content IS '网页内容（抽取正文）';
COMMENT ON COLUMN web_articles.suggested_tags IS 'AI生成的推荐标签（非正式）';

CREATE TRIGGER trg_web_articles_updated_at BEFORE UPDATE ON web_articles
FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- 网页题目子表（题目生成失败时正文仍可入库，题目可后补/为空）
CREATE TABLE webpage_questions (
    id BIGSERIAL PRIMARY KEY,
    article_id BIGINT NOT NULL REFERENCES web_articles(id) ON DELETE CASCADE,
    question TEXT NOT NULL,
    reference_answer TEXT,
    sort_order INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE webpage_questions IS '网页出题子表（3-5 道「读后掌握」问题）';
COMMENT ON COLUMN webpage_questions.article_id IS '所属文章ID（级联删除）';
COMMENT ON COLUMN webpage_questions.question IS '题干';
COMMENT ON COLUMN webpage_questions.reference_answer IS '参考答案（前端折叠展示）';
COMMENT ON COLUMN webpage_questions.sort_order IS '题目顺序';

CREATE INDEX idx_webpage_questions_article ON webpage_questions(article_id);

-- ==========================================
-- 7. 模块三：B站视频
-- ==========================================
-- 视频任务表
CREATE TABLE bilibili_tasks (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    bvid VARCHAR(20) NOT NULL,
    url TEXT NOT NULL DEFAULT '',     -- 原始链接（含 p 分集参数；重试/断点续跑需要）
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCESS', 'FAILED')),
    progress INT DEFAULT 0,
    step_desc VARCHAR(255),
    error_msg TEXT,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, bvid)   -- 同一视频不可重复提交
);

COMMENT ON TABLE bilibili_tasks IS 'B站视频处理任务表（状态机）';
COMMENT ON COLUMN bilibili_tasks.user_id IS '创建人ID';
COMMENT ON COLUMN bilibili_tasks.bvid IS 'B站bvid（与 user_id 联合唯一）';
COMMENT ON COLUMN bilibili_tasks.status IS '任务状态：PENDING, PROCESSING, SUCCESS, FAILED';
COMMENT ON COLUMN bilibili_tasks.progress IS '进度 (0-100)';
COMMENT ON COLUMN bilibili_tasks.step_desc IS '当前步骤中文描述';
COMMENT ON COLUMN bilibili_tasks.error_msg IS '错误信息';

CREATE TRIGGER trg_bilibili_tasks_updated_at BEFORE UPDATE ON bilibili_tasks
FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- 视频知识本体表（任务创建时即初始化，一任务一视频；任务删除时级联删除视频知识）
CREATE TABLE bilibili_videos (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL UNIQUE REFERENCES bilibili_tasks(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id),
    bvid VARCHAR(20) NOT NULL UNIQUE,
    title VARCHAR(255),
    mode VARCHAR(10) CHECK (mode IN ('CC', 'AI', 'AUDIO')),  -- 处理完成后填充
    outline TEXT,                       -- 带时间戳的大纲
    local_video_path VARCHAR(1024),
    local_audio_path VARCHAR(1024),
    local_subtitle_path VARCHAR(1024),  -- 原始字幕文件路径（AUDIO 模式为空）
    suggested_tags TEXT[],              -- AI推荐的标签（非正式）
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE bilibili_videos IS 'B站视频知识表（一任务一视频）';
COMMENT ON COLUMN bilibili_videos.task_id IS '关联任务ID（唯一，任务删除时级联删除本行及子表）';
COMMENT ON COLUMN bilibili_videos.user_id IS '创建人ID';
COMMENT ON COLUMN bilibili_videos.bvid IS 'B站bvid';
COMMENT ON COLUMN bilibili_videos.title IS '视频标题';
COMMENT ON COLUMN bilibili_videos.mode IS '处理模式：CC=官方CC字幕 / AI=B站AI字幕 / AUDIO=音频模式（无字幕）';
COMMENT ON COLUMN bilibili_videos.outline IS '带时间戳的大纲';
COMMENT ON COLUMN bilibili_videos.local_video_path IS '本地视频路径';
COMMENT ON COLUMN bilibili_videos.local_audio_path IS '本地音频路径';
COMMENT ON COLUMN bilibili_videos.local_subtitle_path IS '本地字幕文件路径（音频模式为空）';
COMMENT ON COLUMN bilibili_videos.suggested_tags IS 'AI生成的推荐标签（非正式）';

CREATE TRIGGER trg_bilibili_videos_updated_at BEFORE UPDATE ON bilibili_videos
FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- 字幕段表（CC/AI 模式有数据；AUDIO 模式无数据）
CREATE TABLE video_segments (
    id BIGSERIAL PRIMARY KEY,
    video_id BIGINT NOT NULL REFERENCES bilibili_videos(id) ON DELETE CASCADE,
    start_ts INT NOT NULL,              -- 起始时间（秒）
    end_ts INT,                         -- 结束时间（秒）
    content TEXT NOT NULL,              -- 字幕文本
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE video_segments IS '文本段表（详情页列表可点击跳转；字幕模式为字幕段，音频模式为 ASR 转写段）';
COMMENT ON COLUMN video_segments.video_id IS '所属视频ID（级联删除）';
COMMENT ON COLUMN video_segments.start_ts IS '起始时间（秒）';
COMMENT ON COLUMN video_segments.end_ts IS '结束时间（秒）';
COMMENT ON COLUMN video_segments.content IS '字幕文本';

CREATE INDEX idx_video_segments_video ON video_segments(video_id, start_ts);

-- 视频题目子表（每题关联时间点 + 参考答案）
CREATE TABLE video_questions (
    id BIGSERIAL PRIMARY KEY,
    video_id BIGINT NOT NULL REFERENCES bilibili_videos(id) ON DELETE CASCADE,
    question TEXT NOT NULL,
    reference_answer TEXT,
    ts INT NOT NULL,                    -- 关联时间点（秒）；音频模式为切片起点（粗粒度锚点）
    sort_order INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE video_questions IS '视频出题子表（5-8 道「看后掌握」问题）';
COMMENT ON COLUMN video_questions.video_id IS '所属视频ID（级联删除）';
COMMENT ON COLUMN video_questions.question IS '题干';
COMMENT ON COLUMN video_questions.reference_answer IS '参考答案（前端折叠展示）';
COMMENT ON COLUMN video_questions.ts IS '关联时间点（秒，可跳转；音频模式为切片起点粗粒度锚点）';
COMMENT ON COLUMN video_questions.sort_order IS '题目顺序';

CREATE INDEX idx_video_questions_video ON video_questions(video_id);

-- ==========================================
-- 8. 统一向量检索中枢（当前仅嵌入手打笔记）
-- ==========================================
CREATE TABLE embeddings (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    source_type VARCHAR(20) NOT NULL DEFAULT 'MANUAL',  -- 当前仅 'MANUAL'，预留扩展
    source_id BIGINT NOT NULL,          -- 来源表主键ID（当前为 manual_knowledge.id）
    chunk_content TEXT NOT NULL,        -- 分块后的文本内容
    embedding VECTOR(512),              -- 512 维，与 bge-small-zh-v1.5 一致
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE embeddings IS '向量嵌入表（统一检索入口；当前仅嵌入手打笔记）';
COMMENT ON COLUMN embeddings.user_id IS '所属用户ID';
COMMENT ON COLUMN embeddings.source_type IS '来源类型标识（当前仅 MANUAL）';
COMMENT ON COLUMN embeddings.source_id IS '来源表的主键ID（当前为 manual_knowledge.id）';
COMMENT ON COLUMN embeddings.chunk_content IS '分块后的文本内容';
COMMENT ON COLUMN embeddings.embedding IS '向量数据（512 维，与 bge-small-zh-v1.5 一致）';

-- 索引
CREATE INDEX ON embeddings USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_embeddings_source ON embeddings(source_type, source_id);
CREATE INDEX idx_embeddings_user ON embeddings(user_id);
