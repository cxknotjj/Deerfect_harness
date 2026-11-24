"""plugin-mem0-local：仓库内的精简 mem0 本地服务。

只暴露 Java 客户端使用的两个接口（OSS REST 形状）：
  POST /memories  写入一轮消息
  POST /search    按 query 检索
底层使用 mem0ai 库 + 嵌入式 Chroma（文件持久化），不需要 Docker / PostgreSQL。

配置来自 plugin-mem0-local/.env（python-dotenv 自动加载）：
  MEM0_LLM_BASE_URL / MEM0_LLM_API_KEY / MEM0_LLM_MODEL
  MEM0_EMBEDDER_BASE_URL / MEM0_EMBEDDER_API_KEY / MEM0_EMBEDDER_MODEL
  MEM0_DATA_DIR / MEM0_COLLECTION
"""

from __future__ import annotations

import logging
import os
from contextlib import asynccontextmanager
from typing import Any

from dotenv import load_dotenv

# mem0 在 import 期读取遥测开关，必须先加载 .env
load_dotenv()
os.environ.setdefault("MEM0_TELEMETRY", "false")

from fastapi import Depends, FastAPI  # noqa: E402
from pydantic import BaseModel, Field  # noqa: E402

try:
    from mem0 import Memory
except ImportError:  # 仅测试桩环境允许缺失；真实运行需安装 mem0ai
    Memory = None  # type: ignore[assignment]

logger = logging.getLogger("mem0-local")


@asynccontextmanager
async def lifespan(_: FastAPI):
    """启动预热：加载 Chroma 与 embedding 客户端，避免首轮检索超过 Java 侧读超时。"""
    if os.environ.get("MEM0_SKIP_WARMUP") != "1":
        try:
            get_memory().search(query="warmup", filters={"user_id": "warmup"}, limit=1)
            logger.info("mem0-local warmup done")
        except Exception as exc:  # 预热失败不阻止启动，请求期会返回明确错误
            logger.warning("mem0-local warmup failed: %s", exc)
    yield


app = FastAPI(title="plugin-mem0-local", version="0.1.0", lifespan=lifespan)

_memory: Any | None = None


class Message(BaseModel):
    role: str
    content: str


class AddRequest(BaseModel):
    messages: list[Message]
    user_id: str = Field(min_length=1)
    agent_id: str | None = None
    run_id: str | None = None
    metadata: dict[str, Any] | None = None


class SearchRequest(BaseModel):
    query: str = Field(min_length=1)
    user_id: str = Field(min_length=1)
    agent_id: str | None = None
    limit: int = 5


def _require(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(f"plugin-mem0-local 缺少环境变量: {name}（见 plugin-mem0-local/.env.example）")
    return value


def _llm_api_key() -> str:
    return (os.environ.get("MEM0_LLM_API_KEY")
            or os.environ.get("DEEPSEEK_API_KEY")
            or os.environ.get("QWEN_API_KEY")
            or "").strip()


def _embedder_api_key() -> str:
    return (os.environ.get("MEM0_EMBEDDER_API_KEY")
            or os.environ.get("QWEN_API_KEY")
            or "").strip()


def build_memory() -> Any:
    """按环境变量构建 mem0 Memory（首次请求时初始化）。"""
    if Memory is None:
        raise RuntimeError("未安装 mem0ai：请运行 plugin-mem0-local/start.ps1 或 pip install -r requirements.txt")

    llm_key = _llm_api_key()
    embedder_key = _embedder_api_key()
    if not llm_key:
        raise RuntimeError("缺少 LLM key：请设置 MEM0_LLM_API_KEY（或 QWEN_API_KEY / DEEPSEEK_API_KEY）")
    if not embedder_key:
        raise RuntimeError("缺少 embedding key：请设置 MEM0_EMBEDDER_API_KEY 或 QWEN_API_KEY")

    data_dir = os.environ.get("MEM0_DATA_DIR", "./data")
    os.makedirs(data_dir, exist_ok=True)

    config = {
        # mem0 的 SQLite 历史库：Windows 下默认路径不可写，显式放到本地数据目录
        "history_db_path": os.path.join(data_dir, "history.db"),
        "vector_store": {
            "provider": "chroma",
            "config": {
                "collection_name": os.environ.get("MEM0_COLLECTION", "memories"),
                "path": data_dir,
            },
        },
        "llm": {
            "provider": "openai",
            "config": {
                "api_key": llm_key,
                "openai_base_url": os.environ.get(
                    "MEM0_LLM_BASE_URL", "https://api.deepseek.com/v1"),
                "model": os.environ.get("MEM0_LLM_MODEL", "deepseek-chat"),
            },
        },
        "embedder": {
            "provider": "openai",
            "config": {
                "api_key": embedder_key,
                "openai_base_url": os.environ.get(
                    "MEM0_EMBEDDER_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
                "model": os.environ.get("MEM0_EMBEDDER_MODEL", "text-embedding-v3"),
            },
        },
    }
    return Memory.from_config(config)


def get_memory() -> Any:
    global _memory
    if _memory is None:
        _memory = build_memory()
    return _memory


def _results_of(raw: Any) -> list[dict[str, Any]]:
    """兼容 mem0 不同版本的返回形状：{'results': [...]} 或 [...]。"""
    items = raw.get("results") if isinstance(raw, dict) else raw
    return list(items or [])


def _normalize(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    normalized = []
    for item in items:
        normalized.append({
            "id": item.get("id"),
            "memory": item.get("memory") or item.get("text") or "",
            "score": item.get("score"),
            "metadata": item.get("metadata") or {},
        })
    return normalized


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/memories")
def add_memory(request: AddRequest, memory: Any = Depends(get_memory)) -> dict[str, Any]:
    result = memory.add(
        messages=[message.model_dump() for message in request.messages],
        user_id=request.user_id,
        agent_id=request.agent_id,
        run_id=request.run_id,
        metadata=request.metadata or {},
    )
    return {"results": _results_of(result)}


@app.post("/search")
def search_memory(request: SearchRequest, memory: Any = Depends(get_memory)) -> dict[str, Any]:
    filters: dict[str, Any] = {"user_id": request.user_id}
    if request.agent_id:
        filters["agent_id"] = request.agent_id
    result = memory.search(
        query=request.query,
        filters=filters,
        limit=request.limit,
    )
    return {"results": _normalize(_results_of(result))}

