# -*- coding: utf-8 -*-
"""Batch-replace user-visible hardcoded English UI strings with Chinese."""
import os

# Only user-visible labels. Skip class names, log tags, format patterns, brand names.
M = {
 'Live TV':'直播电视','More Like This':'更多类似','Play':'播放','My List':'我的片单',
 'In My List':'已收藏','Top Picks for You':'为你精选','Because You Watched':'因为你看过',
 'New for You':'为你推荐','Shows':'剧集','Movies':'电影','This Week':'本周','Earlier':'更早',
 'Notifications':'通知','Back':'返回','Home':'首页','Search':'搜索',
 "Couldn't load this title.":'无法加载该影片','Play from beginning':'从头播放',
 'Action':'动作','Crime':'犯罪','Horror':'恐怖','Romance':'爱情','Animation':'动画',
 'Family':'家庭','Adventure':'冒险','Mystery':'悬疑','Continue the Collection':'继续观看系列',
 'Hidden Gems for You':'为你发现的宝藏','Critically Acclaimed':'口碑佳作','More Movies':'更多电影',
 'Today':'今天','Yesterday':'昨天',"You're watching":'正在观看','Paused':'已暂停',
 'Next Episode':'下一集','Off':'关闭','Try Again':'重试','Your Search Recommendations':'搜索推荐',
 'Settings':'设置','Allowed':'已允许','Not allowed':'未允许','Server address':'服务器地址',
 'Cast name':'投屏名称',"Couldn't load this show.":'无法加载该剧集','Profile':'个人中心',
 'Later':'稍后','Flix':'影视','Show':'剧集','Rate':'评分','Not for me':'不感兴趣',
 'I like this':'我喜欢','Love this!':'非常喜欢','Episodes':'剧集列表','Resume':'继续播放',
 'Feed':'推荐','Nothing here yet.':'暂无内容','Comedies':'喜剧片','Dramas':'剧情片',
 'Sci-Fi':'科幻片','Thrillers':'惊悚片','Documentaries':'纪录片','Continue Watching':'继续观看',
 'Dramas Shows':'剧情剧集','Drama Shows':'剧情剧集','Comedies Shows':'喜剧剧集',
 'Comedy Shows':'喜剧剧集','Thrillers Shows':'惊悚剧集','Thriller Shows':'惊悚剧集',
 'Documentaries Shows':'纪录剧集','Documentary Series':'纪录剧集','Titles':'影片',
 'Top 10 Titles':'排行榜前十','Top 10':'十大排行','Recommended for You':'为你推荐',
 'New on HomeFlix':'最新上线','Coming Soon: Movies':'即将上线：电影','Coming Soon: Series':'即将上线：剧集',
 'Trending Now':'热门推荐','TV Shows':'电视剧','New Episodes & Shows':'最新剧集',
 'Top Rated Shows':'高分剧集','More Shows':'更多剧集','Recently Added Movies':'最近添加电影',
 'Watch It Again':'再看一遍','Top 10 Movies on HomeFlix This Week':'本周十大电影',
 'Couldn\'t load notifications':'无法加载通知','All':'全部','NEW':'新',
 "You're all caught up":'没有新通知','Recently':'最近','Just now':'刚刚',
 'Failed to load notifications':'加载通知失败','Restart':'重新播放','Speed':'倍速',
 'Audio':'音轨','Default':'默认','Subtitles':'字幕','Playback Speed':'播放速度',
 'Casting from Chrome on Mac':'正在从 Mac 版 Chrome 投屏',
 "This video format isn't supported on this TV.":'本电视不支持该视频格式',
 'Something went wrong while playing this title.':'播放时出错',
 'Audio & Subtitles':'音轨与字幕','Search movies & shows':'搜索电影和剧集',
 'Comedy':'喜剧','Drama':'剧情','Thriller':'惊悚','Science Fiction':'科幻','Documentary':'纪录片',
 'Your movies and shows, on your own server.':'你的影视库，尽在自己的服务器上',
 'Contact':'联系','GitHub':'开源地址','Open source':'开源',
 'Enjoying HomeFlix? Buy me a coffee':'喜欢 HomeFlix？请我喝杯咖啡',
 "That doesn't look like a server address":'这似乎不是有效的服务器地址',
 'Cast to this TV':'投屏到这台电视','Casting is turned off':'投屏已关闭',
 'Ready to cast':'可以投屏','Not connected to the server':'未连接到服务器',
 'Allow casting to this TV':'允许投屏到这台电视','Allow HomeFlix to open from casting':'允许从投屏打开',
 'About':'关于','Server':'服务器','Reset to default server':'恢复默认服务器',
 'App updates':'应用更新','Updates install from your HomeFlix server':'更新来自你的服务器',
 'HomeFlix is up to date':'已是最新版本','Install update':'安装更新','Check for updates':'检查更新',
 'Allow HomeFlix to install updates':'允许安装更新',"Shown in the web app's cast menu":'显示在网页投屏列表中',
 'The name this TV shows in the HomeFlix web app':'这台电视在网页端显示的名称',
 'Save':'保存','Cancel':'取消','Resume ':'继续播放 ','Play from S1:E1':'从第一季第一集播放',
 'Play S1:E1':'播放第一季第一集','My HomeFlix':'我的影视','Coming Soon':'即将上线',
 'About HomeFlix':'关于','Refresh':'刷新','Exit HomeFlix':'退出',
 'HomeFlix closed unexpectedly last time':'上次异常退出','A photo of this screen helps fix it.':'截图有助于修复问题',
 'Nothing to show':'暂无内容','Server settings':'服务器设置','Install unknown apps':'安装未知应用',
 'Update available':'有可用更新','Update now':'立即更新','Season 3':'第三季','New Series':'新剧集',
 'Notify Me is on':'已开启提醒','Watch now':'立即观看','More Info':'更多信息','Movie':'电影',
 'Unknown':'未知','Loved':'喜欢','Liked':'已喜欢','Rate this title':'为该影片评分',
 'Your ratings shape what HomeFlix recommends':'你的评分会影响推荐内容',
 'Recently Added':'最近添加',
 # strings.xml values
 'Your personal Netflix experience on Android TV':'属于你的安卓电视影院',
 'Error loading content':'加载内容失败','Error loading details':'加载详情失败',
 'No content available':'暂无内容','Network error. Please check your connection.':'网络错误，请检查连接',
 'Unknown error occurred':'发生未知错误','Loading...':'加载中...','Cast & Crew':'演职人员',
 'Director: %s':'导演：%s','Cast: %s':'主演：%s','Details':'详情','Quality: %s':'画质：%s',
 'Language: %s':'语言：%s','%d min':'%d 分钟','Search movies and TV shows...':'搜索电影和剧集...',
 'No results found':'没有找到结果','Try searching with different keywords':'换个关键词试试',
 'Search for your favorite movies and TV shows':'搜索你喜欢的电影和剧集','+ My List':'+ 我的片单',
 '✓ My List':'✓ 我的片单','Go Back':'返回','Featured':'精选','Popular Movies':'热门电影',
 'Popular TV Shows':'热门剧集','Recently Added':'最近添加',
}

# skip identifiers / log tags / brand
SKIP = {'SearchVM','PlayerVM','UTC','HomeFlixMark','DEVELOPED BY','Ferdous Azad',
        'Developer of HomeFlix','Flix','MMM d','About HomeFlix','HomeFlix TV',
        'HomeFlix is up to date','HomeFlix 5.2 is ready','S1:E3 Pilot','S1:E3  Pilot',
        'Show S01E02','Resume ','Play from S1:E1','Play S1:E1'}

count=0; files=0
for dp,_,fs in os.walk('app/src'):
    for f in fs:
        if not (f.endswith('.kt') or f.endswith('.xml')): continue
        p=os.path.join(dp,f)
        s=open(p,encoding='utf-8').read()
        orig=s
        for en,zh in M.items():
            if en in SKIP: continue
            # replace only inside quotes
            s=s.replace('"'+en+'"','"'+zh+'"')
        if s!=orig:
            open(p,'w',encoding='utf-8').write(s); files+=1
            count+=sum(1 for en in M if '"'+en+'"' in orig)
print('changed files:',files,'replacements:',count)
