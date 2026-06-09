"""
监控录像管理服务
@author 翱翔的雄库鲁
@email andywebjava@163.com
@wechat EasyAIoT2025
"""
import io
import logging
import zipfile
from datetime import datetime, timedelta
from typing import Dict, List, Optional

from flask import current_app
from minio.error import S3Error

from models import db, RecordSpace, RecordFile
from app.services.record_space_service import get_minio_client
from app.services.space_file_metadata_service import (
    delete_record_files_metadata,
    sync_record_files_from_minio,
    extract_prefix_from_url,
)

logger = logging.getLogger(__name__)


def list_record_videos(
    space_id: int,
    device_id: Optional[str] = None,
    page_no: int = 1,
    page_size: int = 20,
    search: Optional[str] = None,
    start_time: Optional[datetime] = None,
    end_time: Optional[datetime] = None,
) -> Dict:
    """获取监控录像列表（数据库分页）"""
    try:
        record_space = RecordSpace.query.get_or_404(space_id)
        query = RecordFile.query.filter_by(space_id=space_id)

        effective_device_id = device_id or record_space.device_id
        if effective_device_id:
            query = query.filter(RecordFile.device_id == effective_device_id)

        if search:
            query = query.filter(RecordFile.filename.ilike(f'%{search}%'))
        if start_time:
            query = query.filter(RecordFile.event_time >= start_time)
        if end_time:
            query = query.filter(RecordFile.event_time <= end_time)

        query = query.order_by(RecordFile.event_time.desc())
        pagination = query.paginate(page=page_no, per_page=page_size, error_out=False)

        return {
            'items': [item.to_list_item() for item in pagination.items],
            'total': pagination.total,
            'page_no': page_no,
            'page_size': page_size,
        }
    except Exception as e:
        logger.error(f"获取监控录像列表失败: {str(e)}", exc_info=True)
        raise RuntimeError(f"获取监控录像列表失败: {str(e)}")


def delete_record_videos(space_id: int, object_names: List[str]) -> Dict:
    """批量删除监控录像（MinIO + 数据库）"""
    try:
        record_space = RecordSpace.query.get_or_404(space_id)
        bucket_name = record_space.bucket_name

        minio_client = get_minio_client()
        if not minio_client.bucket_exists(bucket_name):
            raise ValueError(f"监控录像空间的MinIO bucket不存在: {bucket_name}")

        deleted_count = 0
        failed_count = 0
        failed_objects = []

        for object_name in object_names:
            try:
                minio_client.remove_object(bucket_name, object_name)
                thumb_name = object_name.rsplit('.', 1)[0] + '.jpg'
                try:
                    minio_client.remove_object(bucket_name, thumb_name)
                except Exception:
                    pass
                deleted_count += 1
                logger.info(f"删除监控录像成功: {bucket_name}/{object_name}")
            except Exception as e:
                failed_count += 1
                failed_objects.append(object_name)
                logger.warning(f"删除监控录像失败: {bucket_name}/{object_name}, error={str(e)}")

        success_objects = [n for n in object_names if n not in failed_objects]
        delete_record_files_metadata(bucket_name, success_objects)

        return {
            'deleted_count': deleted_count,
            'failed_count': failed_count,
            'failed_objects': failed_objects,
        }
    except Exception as e:
        logger.error(f"批量删除监控录像失败: {str(e)}", exc_info=True)
        raise RuntimeError(f"批量删除监控录像失败: {str(e)}")


def get_record_video(space_id: int, object_name: str):
    """获取监控录像内容"""
    try:
        record_space = RecordSpace.query.get_or_404(space_id)
        bucket_name = record_space.bucket_name

        minio_client = get_minio_client()
        if not minio_client.bucket_exists(bucket_name):
            raise ValueError(f"监控录像空间的MinIO bucket不存在: {bucket_name}")

        try:
            stat = minio_client.stat_object(bucket_name, object_name)
            data = minio_client.get_object(bucket_name, object_name)
            content = data.read()
            data.close()
            data.release_conn()
            return content, stat.content_type or 'video/mp4', object_name.split('/')[-1]
        except S3Error as e:
            if e.code == 'NoSuchKey':
                raise ValueError(f"录像不存在: {object_name}")
            raise
    except Exception as e:
        logger.error(f"获取监控录像失败: {str(e)}", exc_info=True)
        raise RuntimeError(f"获取监控录像失败: {str(e)}")


def cleanup_old_videos_by_days(space_id: int, days: int) -> Dict:
    """根据天数清理旧的监控录像"""
    try:
        record_space = RecordSpace.query.get_or_404(space_id)
        bucket_name = record_space.bucket_name
        save_mode = record_space.save_mode

        cutoff_time = datetime.utcnow() - timedelta(days=days)
        query = RecordFile.query.filter(
            RecordFile.space_id == space_id,
            RecordFile.event_time < cutoff_time,
        )
        if record_space.device_id:
            query = query.filter(RecordFile.device_id == record_space.device_id)

        records = query.all()
        if not records:
            return {'processed_count': 0, 'deleted_count': 0, 'archived_count': 0, 'error_count': 0}

        minio_client = get_minio_client()
        if not minio_client.bucket_exists(bucket_name):
            return {'processed_count': 0, 'deleted_count': 0, 'archived_count': 0, 'error_count': 0}

        archive_bucket_name = current_app.config.get('MINIO_ARCHIVE_BUCKET', 'record-archive')
        if save_mode == 1 and not minio_client.bucket_exists(archive_bucket_name):
            minio_client.make_bucket(archive_bucket_name)

        processed_count = deleted_count = archived_count = error_count = 0

        if save_mode == 0:
            object_names = []
            for record in records:
                try:
                    minio_client.remove_object(bucket_name, record.object_name)
                    if record.thumbnail_url:
                        thumb = extract_prefix_from_url(record.thumbnail_url)
                        if thumb:
                            try:
                                minio_client.remove_object(bucket_name, thumb)
                            except Exception:
                                pass
                    object_names.append(record.object_name)
                    deleted_count += 1
                    processed_count += 1
                except Exception as e:
                    error_count += 1
                    logger.error(f"删除录像失败: {record.object_name}, error={e}")
            delete_record_files_metadata(bucket_name, object_names)
        else:
            device_groups: Dict[str, list] = {}
            for record in records:
                device_groups.setdefault(record.device_id, []).append(record)

            for device_id, record_list in device_groups.items():
                try:
                    zip_buffer = io.BytesIO()
                    removed_names = []
                    with zipfile.ZipFile(zip_buffer, 'w', zipfile.ZIP_DEFLATED) as zip_file:
                        for record in record_list:
                            try:
                                data = minio_client.get_object(bucket_name, record.object_name)
                                file_content = data.read()
                                data.close()
                                data.release_conn()
                                zip_file.writestr(record.filename, file_content)
                                minio_client.remove_object(bucket_name, record.object_name)
                                removed_names.append(record.object_name)
                                deleted_count += 1
                            except Exception as e:
                                error_count += 1
                                logger.error(f"处理录像失败: {record.object_name}, error={e}")

                    if zip_buffer.tell() > 0:
                        zip_buffer.seek(0)
                        archive_object_name = f"{device_id}/{datetime.utcnow().strftime('%Y%m%d_%H%M%S')}.zip"
                        minio_client.put_object(
                            archive_bucket_name,
                            archive_object_name,
                            zip_buffer,
                            length=zip_buffer.tell(),
                            content_type='application/zip',
                        )
                        archived_count += 1
                        processed_count += len(removed_names)
                        delete_record_files_metadata(bucket_name, removed_names)
                except Exception as e:
                    error_count += len(record_list)
                    logger.error(f"归档设备录像失败: device_id={device_id}, error={e}", exc_info=True)

        return {
            'processed_count': processed_count,
            'deleted_count': deleted_count,
            'archived_count': archived_count,
            'error_count': error_count,
        }
    except Exception as e:
        logger.error(f"清理过期录像失败: {str(e)}", exc_info=True)
        raise RuntimeError(f"清理过期录像失败: {str(e)}")


def sync_record_videos_metadata(space_id: int) -> Dict:
    """从 MinIO 同步录像元数据到数据库"""
    return sync_record_files_from_minio(space_id)
