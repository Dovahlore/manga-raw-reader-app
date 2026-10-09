import asyncio
import ctypes
import gc
import os
import pickle
import io
import secrets
from threading import Lock

import uvicorn
from fastapi import FastAPI, HTTPException, Path, Request, Response
from pydantic import BaseModel

from starlette.responses import StreamingResponse

from manga_translator import MangaTranslator

SAFE_PICKLE_MODULES = frozenset({
    'builtins',
    'collections',
    'numpy',
    'numpy.core.multiarray',
    'numpy.dtype',
    'shapely',
    'shapely.io',
    'shapely.geometry',
    'shapely.lib',
    'manga_translator',
    'manga_translator.utils',
    'manga_translator.utils.generic',
    'manga_translator.config'
})

class RestrictedUnpickler(pickle.Unpickler):
    # 放行的模块前缀：
    #   PIL.*            图片对象
    #   shapely.*        文字区域的几何对象（shapely.io.from_wkb 等）
    #   manga_translator.*  本项目自己的类（textblock.TextBlock、utils.generic.Context 等），
    #                      网关与 worker 跑的是同一份代码，必须能反序列化
    SAFE_PREFIXES = ('PIL.', 'shapely.', 'manga_translator.')

    def find_class(self, module: str, name: str):
        if module in SAFE_PICKLE_MODULES or module.startswith(self.SAFE_PREFIXES):
            return super().find_class(module, name)
        raise pickle.UnpicklingError(
            f"Deserialization of {module}.{name} is not allowed"
        )


def restricted_loads(data: bytes):
    return RestrictedUnpickler(io.BytesIO(data)).load()

class MethodCall(BaseModel):
    method_name: str
    attributes: bytes


def release_worker_memory():
    gc.collect()
    try:
        import torch
        torch.cuda.empty_cache()
    except Exception:
        pass
    try:
        libc = ctypes.CDLL(None)
        libc.malloc_trim.argtypes = [ctypes.c_size_t]
        libc.malloc_trim.restype = ctypes.c_int
        libc.malloc_trim(0)
    except Exception:
        pass





class MangaShare:
    def __init__(self, params: dict = None):
        params = dict(params or {})
        # 让“网页/API”这条路也能启用跨页上下文：
        # --context-size 是 CLI 参数，server/main.py 启动 worker 时不会传，所以用环境变量补。
        # 在容器里设 MIT_CONTEXT_SIZE=1 即表示“翻译每一页时带上前一页作为参考”。
        if 'context_size' not in params:
            _cs = os.environ.get('MIT_CONTEXT_SIZE')
            if _cs:
                try:
                    params['context_size'] = int(_cs)
                except ValueError:
                    pass
        self.manga = MangaTranslator(params)
        self.host = params.get('host', '127.0.0.1')
        self.port = int(params.get('port', '5003'))
        nonce = params.get('nonce', None)
        if not nonce:
            nonce = secrets.token_hex(16)
        if nonce == "None":
            nonce = None
        self.nonce = nonce

        # each chunk has a structure like this status_code(int/1byte),len(int/4bytes),bytechunk
        # status codes are 0 for result, 1 for progress report, 2 for error
        self.progress_queue = asyncio.Queue()
        self.lock = Lock()

        async def hook(state: str, finished: bool):
            state_data = state.encode("utf-8")
            progress_data = b'\x01' + len(state_data).to_bytes(4, 'big') + state_data
            await self.progress_queue.put(progress_data)
            await asyncio.sleep(0)

        self.manga.add_progress_hook(hook)

    async def progress_stream(self):
        """
        loops until the status is != 1 which is eiter an error or the result
        """
        while True:
            progress = await self.progress_queue.get()
            yield progress
            if progress[0] != 1:
                break

    async def run_method(self, method, **attributes):
        try:
            if asyncio.iscoroutinefunction(method):
                result = await method(**attributes)
            else:
                result = method(**attributes)

            # 检查是否使用占位符，如果是则创建最小化的结果对象
            if hasattr(result, 'use_placeholder') and result.use_placeholder:
                # 创建一个最小的Context对象，只包含占位符图片，避免传输大量数据
                from manga_translator import Context
                from PIL import Image
                minimal_result = Context()
                minimal_result.result = Image.new('RGB', (1, 1), color='white')
                minimal_result.use_placeholder = True
                result_bytes = pickle.dumps(minimal_result)
            else:
                result_bytes = pickle.dumps(result)

            encoded_result = b'\x00' + len(result_bytes).to_bytes(4, 'big') + result_bytes
            await self.progress_queue.put(encoded_result)
        except Exception as e:
            err_bytes = str(e).encode("utf-8")
            encoded_result = b'\x02' + len(err_bytes).to_bytes(4, 'big') + err_bytes
            await self.progress_queue.put(encoded_result)
        finally:
            self.lock.release()
            # 每页翻完释放图片/中间张量：全书翻译连翻几百页，不释放会无界累积把 VM 内存吃爆
            release_worker_memory()


    def check_nonce(self, request: Request):
        if self.nonce:
            nonce = request.headers.get('X-Nonce')
            if nonce != self.nonce:
                raise HTTPException(401, detail="Nonce does not match")

    def check_lock(self):
        if not self.lock.acquire(blocking=False):
            raise HTTPException(status_code=429, detail="some Method is already being executed.")

    def get_fn(self, method_name: str):
        if method_name.startswith("__"):
            raise HTTPException(status_code=403, detail="These functions are not allowed to be executed remotely")
        method = getattr(self.manga, method_name, None)
        if not method:
            raise HTTPException(status_code=404, detail="Method not found")
        return method

    async def listen(self, translation_params: dict = None):
        app = FastAPI()

        @app.get("/is_locked")
        async def is_locked():
            if self.lock.locked():
                return {"locked": True}
            return {"locked": False}

        @app.post("/simple_execute/{method_name}")
        async def execute_method(request: Request, method_name: str = Path(...)):
            self.check_nonce(request)
            self.check_lock()
            method = self.get_fn(method_name)
            attr = restricted_loads(await request.body())
            # 非流式调用：必须清掉上一次流式请求留下的占位符开关。
            # `_is_streaming_mode` 挂在常驻实例上（见 /execute 路由），网页端流式翻译一次
            # 就会把它置 True，而本路由从不清它 —— 结果之后每个非流式请求
            # （App 接口、脚本、comicread 等）都只拿到 1x1 白图占位符。
            self.manga._is_streaming_mode = False
            result = None
            try:
                if asyncio.iscoroutinefunction(method):
                    result = await method(**attr)
                else:
                    result = method(**attr)
                self.lock.release()
                result_bytes = pickle.dumps(result)
                return Response(content=result_bytes, media_type="application/octet-stream")
            except Exception as e:
                self.lock.release()
                raise HTTPException(status_code=500, detail=str(e))
            finally:
                # 每页翻完立刻释放图片/中间张量，避免全书翻译时内存无界累积
                del result
                release_worker_memory()

        @app.post("/execute/{method_name}")
        async def execute_method(request: Request, method_name: str = Path(...)):
            self.check_nonce(request)
            self.check_lock()
            method = self.get_fn(method_name)
            attr = restricted_loads(await request.body())

            # 根据端点类型决定是否使用占位符优化
            config = attr.get('config')
            self.manga._is_streaming_mode = getattr(config, '_web_frontend_optimized', False) if config else False

            # streaming response
            streaming_response = StreamingResponse(self.progress_stream(), media_type="application/octet-stream")
            asyncio.create_task(self.run_method(method, **attr))
            return streaming_response

        config = uvicorn.Config(app, host=self.host, port=self.port)
        server = uvicorn.Server(config)
        await server.serve()
