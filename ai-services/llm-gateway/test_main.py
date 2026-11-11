import base64
import os
import unittest
from unittest.mock import patch

import main
from fastapi import HTTPException


class GatewayContractTest(unittest.TestCase):
    def test_glm_46v_flash_declares_visual_capability(self):
        preset = main.PROVIDERS["zhipu"]
        self.assertIn("glm-4.6v-flash", preset.models)
        self.assertIn("glm-4.6v-flash", preset.multimodal_models)

    def test_zhipu_key_is_environment_only(self):
        request = main.ProviderRequest(messages=[], provider="zhipu", api_key="browser-secret")
        with patch.dict(os.environ, {"ZHIPU_API_KEY": "server-secret"}):
            key, base = main._resolve_key_base(request, "")
        self.assertEqual("server-secret", key)
        self.assertEqual(main.PROVIDERS["zhipu"].base_url, base)

    def test_more_than_six_images_is_rejected(self):
        image = "data:image/png;base64," + base64.b64encode(b"x").decode()
        message = main.Message(role="user", content=[
            {"type": "image_url", "image_url": {"url": image}} for _ in range(7)])
        with self.assertRaises(HTTPException) as raised:
            main._localize_image_urls([message])
        self.assertEqual(413, raised.exception.status_code)

    def test_persistent_429_has_capacity_code_and_no_secret(self):
        class CapacityError(Exception):
            status_code = 429
            response = type("Response", (), {"headers": {"Retry-After": "0"}})()
        def upstream(*_):
            raise CapacityError("sensitive-upstream-body")
            yield
        request = main.ProviderRequest(messages=[main.Message(role="user", content="hello")])
        with patch.object(main.time, "sleep", lambda _: None), patch.object(main.random, "uniform", lambda *_: 0):
            events = list(main._stream_with_retry(upstream, request, "key", "base", "test"))
        self.assertIn("BLOCKED_UPSTREAM_CAPACITY", events[-1]["data"])
        self.assertNotIn("sensitive-upstream-body", events[-1]["data"])


if __name__ == "__main__": unittest.main()
