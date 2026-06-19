"""Rocket runtime helper for OpenWhisk Python actions.

The invoker sends ordinary /run JSON requests augmented with Rocket fields:
rocket_phase, rocket_state, rocket_library, rocket_front_model, and
rocket_back_model. This helper keeps imported libraries and model slices in
module-level caches so that later invocations can reuse them.
"""

from __future__ import annotations

import importlib
import os
import pickle
import threading
from dataclasses import dataclass
from typing import Any, Callable, Dict, Mapping, Optional


ROCKET_PHASE = "rocket_phase"
ROCKET_STATE = "rocket_state"
ROCKET_PRELOAD = "preload"
ROCKET_INVOKE = "invoke"


@dataclass
class RocketContext:
    phase: str
    state: str
    library: Optional[str]
    base_model: Optional[str]
    front_model: Optional[str]
    back_model: Optional[str]


class RocketContainerManager:
    """Hierarchical ML artifact loader used inside an OpenWhisk action."""

    def __init__(
        self,
        front_loader: Optional[Callable[[Mapping[str, Any]], Any]] = None,
        back_loader: Optional[Callable[[Mapping[str, Any]], Any]] = None,
        full_loader: Optional[Callable[[Mapping[str, Any]], Any]] = None,
    ) -> None:
        self._lock = threading.RLock()
        self._libraries: Dict[str, Any] = {}
        self._front_models: Dict[str, Any] = {}
        self._back_models: Dict[str, Any] = {}
        self._full_models: Dict[str, Any] = {}
        self._front_loader = front_loader or self._load_pickle_from_path
        self._back_loader = back_loader or self._load_pickle_from_path
        self._full_loader = full_loader

    def handle(self, payload: Mapping[str, Any], user_handler: Callable[[Mapping[str, Any], "RocketContainerManager"], Any]) -> Any:
        context = self.context(payload)
        if context.phase == ROCKET_PRELOAD:
            self.transition(context, payload)
            return {"rocket": "ok", "state": context.state}
        self.transition(context, payload)
        return user_handler(payload, self)

    def context(self, payload: Mapping[str, Any]) -> RocketContext:
        return RocketContext(
            phase=str(payload.get(ROCKET_PHASE, os.environ.get("__OW_ROCKET_PHASE", ROCKET_INVOKE))),
            state=str(payload.get(ROCKET_STATE, os.environ.get("__OW_ROCKET_STATE", "warming"))),
            library=self._field(payload, "rocket_library", "__OW_ROCKET_LIBRARY"),
            base_model=self._field(payload, "rocket_base_model", "__OW_ROCKET_BASE_MODEL"),
            front_model=self._field(payload, "rocket_front_model", "__OW_ROCKET_FRONT_MODEL"),
            back_model=self._field(payload, "rocket_back_model", "__OW_ROCKET_BACK_MODEL"),
        )

    def transition(self, context: RocketContext, payload: Mapping[str, Any]) -> None:
        with self._lock:
            if context.state in ("imported", "p-loaded", "f-loaded"):
                self.import_library(context.library)
            if context.state in ("p-loaded", "f-loaded"):
                self.front_model(context.front_model, payload)
            if context.state == "f-loaded":
                self.back_model(context.back_model, payload)

    def import_library(self, name: Optional[str]) -> Any:
        if not name:
            return None
        if name not in self._libraries:
            self._libraries[name] = importlib.import_module(name)
        return self._libraries[name]

    def front_model(self, key: Optional[str], payload: Mapping[str, Any]) -> Any:
        if not key:
            return None
        if key not in self._front_models:
            self._front_models[key] = self._front_loader({"path": key, "payload": payload, "manager": self})
        return self._front_models[key]

    def back_model(self, key: Optional[str], payload: Mapping[str, Any]) -> Any:
        if not key:
            return None
        if key not in self._back_models:
            self._back_models[key] = self._back_loader({"path": key, "payload": payload, "manager": self})
        return self._back_models[key]

    def full_model(self, key: str, payload: Mapping[str, Any]) -> Any:
        if key not in self._full_models:
            if self._full_loader:
                self._full_models[key] = self._full_loader({"path": key, "payload": payload, "manager": self})
            else:
                self._full_models[key] = (self.front_model(key, payload), self.back_model(key, payload))
        return self._full_models[key]

    @staticmethod
    def _field(payload: Mapping[str, Any], key: str, env: str) -> Optional[str]:
        value = payload.get(key, os.environ.get(env))
        return str(value) if value not in (None, "") else None

    @staticmethod
    def _load_pickle_from_path(meta: Mapping[str, Any]) -> Any:
        path = str(meta["path"])
        if not os.path.exists(path):
            return {"path": path, "loaded": False}
        with open(path, "rb") as handle:
            return pickle.load(handle)


manager = RocketContainerManager()


def rocket_main(user_handler: Callable[[Mapping[str, Any], RocketContainerManager], Any]) -> Callable[[Mapping[str, Any]], Any]:
    def wrapped(payload: Mapping[str, Any]) -> Any:
        return manager.handle(payload, user_handler)

    return wrapped

