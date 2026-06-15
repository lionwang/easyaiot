"""SAM3 模型权重下载与状态查询（下载到本地 SAM_MODEL_PATH，不走 MinIO）"""
import os
import threading
import urllib.request
from typing import Any, Dict

from app.services.sam_service import SAM_MODEL_PATH

SAM_MODEL_DOWNLOAD_URL = os.getenv('SAM_MODEL_DOWNLOAD_URL', '').strip()
MIN_MODEL_SIZE_BYTES = 100 * 1024 * 1024
ESTIMATED_MODEL_SIZE_BYTES = int(os.getenv('SAM_MODEL_ESTIMATED_BYTES', str(3500 * 1024 * 1024)))
DOWNLOAD_CHUNK_SIZE = 1024 * 1024
DOWNLOAD_USER_AGENT = 'EasyAIoT-AI/1.0'

_lock = threading.Lock()
_state: Dict[str, Any] = {
    'status': 'idle',
    'stage': 'idle',
    'progress': 0,
    'downloaded_bytes': 0,
    'total_bytes': 0,
    'error': None,
}


def _reset_error_if_idle() -> None:
    if _state['status'] == 'idle':
        _state['error'] = None


def is_sam_model_available() -> bool:
    if not os.path.isfile(SAM_MODEL_PATH):
        return False
    try:
        return os.path.getsize(SAM_MODEL_PATH) >= MIN_MODEL_SIZE_BYTES
    except OSError:
        return False


def _build_status_locked() -> Dict[str, Any]:
    exists = is_sam_model_available()
    size_bytes = os.path.getsize(SAM_MODEL_PATH) if exists else 0
    _reset_error_if_idle()
    downloading = _state['status'] == 'downloading'
    stage = _state['stage']
    if exists:
        stage = 'done'
    elif not downloading and _state['status'] == 'error':
        stage = 'error'
    elif not downloading:
        stage = 'idle'
    return {
        'exists': exists,
        'filename': os.path.basename(SAM_MODEL_PATH),
        'path': SAM_MODEL_PATH,
        'size_bytes': size_bytes,
        'downloading': downloading,
        'stage': stage,
        'progress': int(_state['progress']) if downloading or exists else 0,
        'downloaded_bytes': int(_state['downloaded_bytes']),
        'total_bytes': int(_state['total_bytes']),
        'error': _state['error'],
    }


def get_sam_model_status() -> Dict[str, Any]:
    with _lock:
        return _build_status_locked()


def _set_progress(stage: str, progress: int, downloaded: int = 0, total: int = 0) -> None:
    with _lock:
        _state['stage'] = stage
        _state['downloaded_bytes'] = downloaded
        if total > 0:
            _state['total_bytes'] = total
        _state['progress'] = max(int(_state['progress']), int(progress))


def _download_http_with_progress(url: str, dest_path: str) -> None:
    req = urllib.request.Request(url, headers={'User-Agent': DOWNLOAD_USER_AGENT})
    with urllib.request.urlopen(req, timeout=300) as resp:
        content_length = int(resp.headers.get('Content-Length', 0) or 0)
        total = content_length or ESTIMATED_MODEL_SIZE_BYTES
        _set_progress('downloading', 1, downloaded=0, total=total)

        downloaded = 0
        with open(dest_path, 'wb') as out_file:
            while True:
                chunk = resp.read(DOWNLOAD_CHUNK_SIZE)
                if not chunk:
                    break
                out_file.write(chunk)
                downloaded += len(chunk)
                progress = min(95, int(downloaded * 95 / total)) if total else 0
                _set_progress('downloading', progress, downloaded=downloaded, total=total)


def _do_download() -> None:
    partial_path = f'{SAM_MODEL_PATH}.downloading'
    try:
        if not SAM_MODEL_DOWNLOAD_URL:
            raise RuntimeError(
                f'未配置 SAM_MODEL_DOWNLOAD_URL，无法自动下载。'
                f'请设置下载地址，或手动将权重放到 {SAM_MODEL_PATH}'
            )

        with _lock:
            _state['status'] = 'downloading'
            _state['stage'] = 'downloading'
            _state['progress'] = 0
            _state['downloaded_bytes'] = 0
            _state['total_bytes'] = ESTIMATED_MODEL_SIZE_BYTES
            _state['error'] = None

        os.makedirs(os.path.dirname(SAM_MODEL_PATH) or '.', exist_ok=True)
        _download_http_with_progress(SAM_MODEL_DOWNLOAD_URL, partial_path)

        size = os.path.getsize(partial_path)
        if size < MIN_MODEL_SIZE_BYTES:
            raise RuntimeError(f'下载文件过小（{size} bytes），可能不完整')

        _set_progress('installing', 96, downloaded=size, total=size)
        os.replace(partial_path, SAM_MODEL_PATH)

        with _lock:
            _state['status'] = 'done'
            _state['stage'] = 'done'
            _state['progress'] = 100
            _state['downloaded_bytes'] = os.path.getsize(SAM_MODEL_PATH)
            _state['total_bytes'] = _state['downloaded_bytes']
            _state['error'] = None
    except Exception as exc:
        if os.path.isfile(partial_path):
            try:
                os.remove(partial_path)
            except OSError:
                pass
        with _lock:
            _state['status'] = 'error'
            _state['stage'] = 'error'
            _state['error'] = str(exc)
    finally:
        try:
            from app.services.sam_service import reset_sam_service
            reset_sam_service()
        except Exception:
            pass


def start_sam_model_download() -> Dict[str, Any]:
    with _lock:
        if is_sam_model_available():
            _state['status'] = 'done'
            _state['stage'] = 'done'
            _state['progress'] = 100
            _state['error'] = None
            return {'started': False, 'message': '模型已存在', **_build_status_locked()}

        if _state['status'] == 'downloading':
            return {'started': False, 'message': '模型正在下载中', **_build_status_locked()}

        _state['status'] = 'downloading'
        _state['stage'] = 'downloading'
        _state['progress'] = 0
        _state['downloaded_bytes'] = 0
        _state['total_bytes'] = ESTIMATED_MODEL_SIZE_BYTES
        _state['error'] = None
        status = _build_status_locked()

    thread = threading.Thread(target=_do_download, name='sam-model-download', daemon=True)
    thread.start()
    return {'started': True, 'message': '已开始下载', **status}
