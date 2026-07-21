import warnings
warnings.simplefilter("ignore")

import asyncio
import sys
import argparse
from f2.apps.douyin.handler import DouyinHandler
from f2.log.logger import logger
import json
import os

# 解决 GBK 编码问题
sys.stdout.reconfigure(encoding="utf-8")
logger.setLevel('ERROR')

def write_to_file(data, output_file: str) -> bool:
    """
    将数据写入文件
    Args:
        data: 要写入的数据
        output_file: 输出文件路径
    Returns:
        bool: 是否写入成功
    """
    try:
        # 创建目录
        os.makedirs(os.path.dirname(output_file), exist_ok=True)
    except Exception as e:
        print(f"创建目录时出错: {e}")
        return False
    
    try:
        # 删除已存在的文件
        if os.path.exists(output_file):
            os.remove(output_file)
    except Exception as e:
        print(f"删除文件时出错: {e}")
        return False
    
    try:
        # 写入新文件
        with open(output_file, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        return True
    except Exception as e:
        print(f"写入文件时出错: {e}")
        return False

# 获取视频信息的方法
async def fetch_video(cookie: str, aweme_id: str):
    kwargs = {
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0",
            "Referer": "https://www.douyin.com/",
        },
        "cookie": cookie,
        "proxies": {"http": None, "https": None},
    }
    
    handler = DouyinHandler(kwargs)
    setattr(handler, "enable_bark", False)
    
    video = await handler.fetch_one_video(aweme_id=aweme_id)
    jsonres = {
        "cover": [video.cover],
        "aweme_id": video.aweme_id,
        "desc": video.desc,
        "video_play_addr": json.dumps(video.video_play_addr),
        "nickname": video.nickname,
        "avatar_thumb": video._get_attr_value("$.aweme_detail.author.avatar_thumb.url_list[0]"),
        "uid": video._get_attr_value("$.aweme_detail.author.sec_uid"),
        "create_time": video.create_time
    }
    print(jsonres)
    
# 获取视频信息的方法
async def fetch_post_data(cookie: str, aweme_id: str, output_file: str):
    kwargs = {
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0",
            "Referer": "https://www.douyin.com/",
        },
        "cookie": cookie,
        "proxies": {"http": None, "https": None},
    }
    
    handler = DouyinHandler(kwargs)
    setattr(handler, "enable_bark", False)
    
    video = await handler.fetch_one_video(aweme_id=aweme_id)

    if write_to_file(video._to_raw(), output_file):
        print("stream-vault-ok")

# Return one raw work as JSON without creating an output file. The Java adapter
# uses this path for parse/preview so media and persistence remain side-effect free.
async def fetch_work_data(cookie: str, aweme_id: str):
    kwargs = {
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0",
            "Referer": "https://www.douyin.com/",
        },
        "cookie": cookie,
        "proxies": {"http": None, "https": None},
    }

    handler = DouyinHandler(kwargs)
    setattr(handler, "enable_bark", False)
    work = await handler.fetch_one_video(aweme_id=aweme_id)
    print(json.dumps(work._to_raw(), ensure_ascii=False))

# 获取用户点赞列表方法
async def fetch_user_like_videos(cookie: str, uid: str, maxc: str, output_file: str):
    kwargs = {
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0",
            "Referer": "https://www.douyin.com/",
        },
        "timeout": 10,
        "cookie": cookie,
        "proxies": {"http": None, "https": None},
    }
    handler = DouyinHandler(kwargs)
    setattr(handler, "enable_bark", False)
    all_videos = []
    async for aweme_data_list in handler.fetch_user_like_videos(
        uid, 0,  20, int(maxc)
    ):
        videos = aweme_data_list._to_list()
        for video in videos:
            jsonres = {
                "cover": [video["cover"]],
                "aweme_id": video["aweme_id"],
                "desc": video["desc"],
                "video_play_addr": json.dumps(video["video_play_addr"]),
                "nickname": video["nickname"],
                "avatar_thumb": video['author_avatar_thumb'],
                "uid": video["uid"],
                "create_time": video["create_time"]
            }
            all_videos.append(jsonres)
    
    if write_to_file(all_videos, output_file):
        print("stream-vault-ok")

# 获取用户视频发布列表方法
async def fetch_user_post_videos(cookie: str, uid: str, maxc: str, output_file: str):
    kwargs = {
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0",
            "Referer": "https://www.douyin.com/",
        },
        "timeout": 10,
        "cookie": cookie,
        "proxies": {"http": None, "https": None},
    }
    handler = DouyinHandler(kwargs)
    setattr(handler, "enable_bark", False)
    all_videos = []
    async for aweme_data_list in handler.fetch_user_post_videos(
        uid, 0, 0,  20, int(maxc)
    ):
        videos = aweme_data_list._to_list()
        for video in videos:
            jsonres = {
                "cover": [video["cover"]],
                "aweme_id": video["aweme_id"],
                "desc": video["desc"],
                "video_play_addr": json.dumps(video["video_play_addr"]),
                "nickname": video["nickname"],
                "avatar_thumb": video['author_avatar_thumb'],
                "uid": video["uid"],
                "create_time": video["create_time"]
            }
            all_videos.append(jsonres)
    
    if write_to_file(all_videos, output_file):
        print("stream-vault-ok")

# 获取收藏夹名称及id
async def fetch_user_collects(cookie: str):
    # 设置日志级别为CRITICAL，只显示严重错误
    logger.setLevel('CRITICAL')
    
    kwargs = {
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0",
            "Referer": "https://www.douyin.com/",
        },
        "timeout": 10,
        "cookie": cookie,
        "proxies": {"http": None, "https": None},
    }
    handler = DouyinHandler(kwargs)
    setattr(handler, "enable_bark", False)
    all_collects = []
    async for collection_list in handler.fetch_user_collects(
        max_cursor=0,
        page_counts=10,
        max_counts=40,
    ):
        raw_data = collection_list._to_raw()
        if 'collects_list' in raw_data:
            for collect in raw_data['collects_list']:
                all_collects.append({
                    "collects_id": collect['collects_id'],
                    "collects_name": collect['collects_name']
                })
    print("stream-vault-start-collects",json.dumps(all_collects, ensure_ascii=False),"stream-vault-end-collects")

# 获取收藏夹下的视频
async def fetch_user_collects_videos(cookie: str, cid: str, maxc:str, output_file: str):
    kwargs = {
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0",
            "Referer": "https://www.douyin.com/",
        },
        "timeout": 10,
        "cookie": cookie,
        "proxies": {"http": None, "https": None},
    }
    handler = DouyinHandler(kwargs)
    setattr(handler, "enable_bark", False)
    all_videos = []
    async for collection_list in handler.fetch_user_collects_videos(
        collects_id=cid,
        max_cursor=0,
        page_counts=10,
        max_counts=int(maxc)
    ):
        print(collection_list._to_raw())
        videos = collection_list._to_list()
        for video in videos:
            jsonres = {
                "cover": [video["cover"]],
                "aweme_id": video["aweme_id"],
                "desc": video["desc"],
                "video_play_addr": json.dumps(video["video_play_addr"]),
                "nickname": video["nickname"],
                "avatar_thumb": video['author_avatar_thumb'],
                "uid": video["uid"],
                "create_time": video["create_time"]
            }
            all_videos.append(jsonres)
    
    if write_to_file(all_videos, output_file):
        print("stream-vault-ok")


# 获取首页推荐
async def fetch_user_feed_videos(cookie: str, sec_user_id: str, output_file: str):
    print(sec_user_id)
    kwargs = {
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0",
            "Referer": "https://www.douyin.com/",
        },
        "timeout": 10,
        "cookie": cookie,
        "proxies": {"http": None, "https": None},
    }
    handler = DouyinHandler(kwargs)
    setattr(handler, "enable_bark", False)
    all_videos = []
    async for feed_list in handler.fetch_user_feed_videos(
        sec_user_id,
        max_cursor=0,
        page_counts=10,
        max_counts=20,
    ):
        print(feed_list._to_raw())
        videos = feed_list._to_list()
        for video in videos:
            jsonres = {
                "cover": [video["cover"]],
                "aweme_id": video["aweme_id"],
                "desc": video["desc"],
                "video_play_addr": json.dumps(video["video_play_addr"]),
                "nickname": video["nickname"],
                "uid": video["uid"],
                "create_time": video["create_time"]
            }
            all_videos.append(jsonres)
    
    if write_to_file(all_videos, output_file):
        print("stream-vault-ok")


# 主函数
async def main():
    parser = argparse.ArgumentParser(description="Douyin API Helper")
    subparsers = parser.add_subparsers(dest="command", help="sub-command help")
    
    # 单视频解析
    fetch_video_parser = subparsers.add_parser("fetch_video", help="Fetch a video from Douyin")
    fetch_video_parser.add_argument("--cookie", type=str, required=True, help="Douyin cookie")
    fetch_video_parser.add_argument("--aweme_id", type=str, required=True, help="Aweme ID of the video")
    
    # 获取用户点赞视频
    fetch_user_like_videos_parser = subparsers.add_parser("fetch_user_like_videos", help="Fetch user_like info from Douyin")
    fetch_user_like_videos_parser.add_argument("--cookie", type=str, required=True, help="Douyin cookie")
    fetch_user_like_videos_parser.add_argument("--uid", type=str, required=True, help="User ID")
    fetch_user_like_videos_parser.add_argument("--maxc", type=str, required=True, help="maxc")
    fetch_user_like_videos_parser.add_argument("--output", type=str, required=True, help="Output file path")

    # 获取用户发布的作品
    fetch_user_post_videos_parser = subparsers.add_parser("fetch_user_post_videos", help="Fetch user_post info from Douyin")
    fetch_user_post_videos_parser.add_argument("--cookie", type=str, required=True, help="Douyin cookie")
    fetch_user_post_videos_parser.add_argument("--uid", type=str, required=True, help="User ID")
    fetch_user_post_videos_parser.add_argument("--maxc", type=str, required=True, help="maxc")
    fetch_user_post_videos_parser.add_argument("--output", type=str, required=True, help="Output file path")
   
   
    #获取用户收藏夹
    fetch_user_collects_parser = subparsers.add_parser("fetch_user_collects", help="Fetch user_collects info from Douyin")
    fetch_user_collects_parser.add_argument("--cookie", type=str, required=True, help="Douyin cookie")

    #获取对应收藏夹的视频
    fetch_user_collects_videos_parser = subparsers.add_parser("fetch_user_collects_videos", help="Fetch user_collects_video info from Douyin")
    fetch_user_collects_videos_parser.add_argument("--cookie", type=str, required=True, help="Douyin cookie")
    fetch_user_collects_videos_parser.add_argument("--cid", type=str, required=True, help="Collect ID")
    fetch_user_collects_videos_parser.add_argument("--maxc", type=str, required=True, help="maxc")
    fetch_user_collects_videos_parser.add_argument("--output", type=str, required=True, help="Output file path")


    # 获取首页推荐
    fetch_user_feed_videos_parser = subparsers.add_parser("fetch_user_feed_videos", help="Fetch user_post info from Douyin")
    fetch_user_feed_videos_parser.add_argument("--cookie", type=str, required=True, help="Douyin cookie")
    fetch_user_feed_videos_parser.add_argument("--uid", type=str, required=True, help="User ID")
    fetch_user_feed_videos_parser.add_argument("--output", type=str, required=True, help="Output file path")
    
    # 获取作品通用 
    fetch_user_post_parser = subparsers.add_parser("fetch_post_data", help="Fetch a Post from Douyin")
    fetch_user_post_parser.add_argument("--cookie", type=str, required=True, help="Douyin cookie")
    fetch_user_post_parser.add_argument("--aweme_id", type=str, required=True, help="Aweme ID of the video")
    fetch_user_post_parser.add_argument("--output", type=str, required=True, help="Output file path")

    fetch_work_data_parser = subparsers.add_parser("fetch_work_data", help="Fetch one raw work as JSON")
    fetch_work_data_parser.add_argument("--cookie", type=str, required=True, help="Douyin cookie")
    fetch_work_data_parser.add_argument("--aweme_id", type=str, required=True, help="Aweme ID")

    args = parser.parse_args()
    
    if args.command == "fetch_video":
        await fetch_video(args.cookie, args.aweme_id)
    if args.command == "fetch_user_like_videos":
        await fetch_user_like_videos(args.cookie, args.uid ,args.maxc, args.output)
    if args.command == "fetch_user_post_videos":
        await fetch_user_post_videos(args.cookie, args.uid ,args.maxc, args.output)
    if args.command == "fetch_user_collects":
        await fetch_user_collects(args.cookie)
    if args.command == "fetch_user_collects_videos":
        await fetch_user_collects_videos(args.cookie, args.cid ,args.maxc, args.output)
    if args.command == "fetch_user_feed_videos":
        await fetch_user_feed_videos(args.cookie, args.uid, args.output)
    if args.command == "fetch_post_data":
    	await fetch_post_data(args.cookie, args.aweme_id, args.output)
    if args.command == "fetch_work_data":
        await fetch_work_data(args.cookie, args.aweme_id)

if __name__ == "__main__":
    asyncio.run(main())
