"""plugin-mem0-local 接口契约测试：用假 Memory 校验请求映射与响应归一化，不依赖真实模型。"""

import os

os.environ["MEM0_SKIP_WARMUP"] = "1"

from fastapi.testclient import TestClient

import main


class FakeMemory:
    def __init__(self) -> None:
        self.added = None
        self.searched = None

    def add(self, messages, user_id, agent_id, run_id, metadata):
        self.added = {
            "messages": messages,
            "user_id": user_id,
            "agent_id": agent_id,
            "run_id": run_id,
            "metadata": metadata,
        }
        return {"results": [{"id": "m1", "memory": "用户叫小林", "event": "ADD"}]}

    def search(self, query, filters, limit):
        self.searched = {"query": query, "filters": filters, "limit": limit}
        return {"results": [{"id": "m1", "memory": "用户叫小林", "score": 0.82, "metadata": {"turn_id": "t1"}}]}


def client_with(fake: FakeMemory) -> TestClient:
    main.app.dependency_overrides[main.get_memory] = lambda: fake
    return TestClient(main.app)


def test_add_maps_request_fields():
    fake = FakeMemory()
    response = client_with(fake).post("/memories", json={
        "messages": [
            {"role": "user", "content": "我叫小林"},
            {"role": "assistant", "content": "记住了"},
        ],
        "user_id": "cli",
        "agent_id": "general",
        "run_id": "s1",
        "metadata": {"turn_id": "t1"},
    })

    assert response.status_code == 200
    assert fake.added["user_id"] == "cli"
    assert fake.added["agent_id"] == "general"
    assert fake.added["run_id"] == "s1"
    assert fake.added["metadata"] == {"turn_id": "t1"}
    assert fake.added["messages"][0] == {"role": "user", "content": "我叫小林"}


def test_search_normalizes_results():
    fake = FakeMemory()
    response = client_with(fake).post("/search", json={
        "query": "我叫什么",
        "user_id": "cli",
        "agent_id": "general",
        "limit": 5,
    })

    assert response.status_code == 200
    assert fake.searched == {
        "query": "我叫什么",
        "filters": {"user_id": "cli", "agent_id": "general"},
        "limit": 5,
    }
    body = response.json()
    assert body["results"][0]["memory"] == "用户叫小林"
    assert body["results"][0]["score"] == 0.82


def test_missing_user_id_is_rejected():
    response = client_with(FakeMemory()).post("/search", json={"query": "x"})

    assert response.status_code == 422

