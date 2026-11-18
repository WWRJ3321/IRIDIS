package com.iris.localai.imagegen

/**
 * 内置词库（扩充部分）：每行「分类|中文名|英文提示词|Danbooru 写法」。只收通用词，不含品牌和作品角色名。
 * 由 [PromptTags.BUILTIN] 解析并接在原有内置标签之后。
 */
internal object PromptTagData {
    const val TABLE = """
QUALITY|杰作|masterpiece|masterpiece
QUALITY|最佳质量|best quality|best quality
QUALITY|超高清|ultra high resolution, 8k|highres, absurdres
QUALITY|精细刻画|extremely detailed|extremely detailed
QUALITY|精致面部|detailed face|detailed face
QUALITY|精致眼睛|beautiful detailed eyes|beautiful detailed eyes
QUALITY|精致发丝|detailed hair|detailed hair
QUALITY|精致纹理|detailed textures|detailed textures
QUALITY|专业摄影|professional photography|professional photography
QUALITY|高动态范围|HDR|hdr
QUALITY|胶片颗粒|film grain|film grain
QUALITY|清晰|crisp, clear|clear
QUALITY|官方画风|official art|official art
QUALITY|壁纸级|wallpaper quality|wallpaper
QUALITY|美学评分高|aesthetic, very aesthetic|very aesthetic
QUALITY|插画质感|illustration|illustration
STYLE|写实|realistic|realistic
STYLE|胶片摄影|analog film photo|film photography
STYLE|即时成像|instant film photo|instant film
STYLE|电影剧照|cinematic film still|cinematic
STYLE|厚涂|impasto painting, thick paint|impasto
STYLE|平涂|flat color illustration|flat color
STYLE|赛璐璐|cel shading|cel shading
STYLE|线稿|line art|lineart
STYLE|素描|pencil sketch|sketch
STYLE|炭笔|charcoal drawing|charcoal (medium)
STYLE|彩铅|colored pencil drawing|colored pencil (medium)
STYLE|马克笔|marker drawing|marker (medium)
STYLE|粉彩|pastel drawing|pastel (medium)
STYLE|水粉|gouache painting|gouache (medium)
STYLE|丙烯|acrylic painting|acrylic paint (medium)
STYLE|浮世绘|ukiyo-e woodblock print|ukiyo-e
STYLE|工笔画|chinese gongbi painting|traditional chinese painting
STYLE|国风|chinese style|chinese style
STYLE|和风|japanese style|japanese clothes, traditional media
STYLE|复古|vintage, retro style|retro artstyle
STYLE|90 年代动画|90s anime style|1990s (style)
STYLE|80 年代|80s retro aesthetic|1980s (style)
STYLE|蒸汽波|vaporwave|vaporwave
STYLE|蒸汽朋克|steampunk|steampunk
STYLE|奇幻|fantasy art|fantasy
STYLE|科幻|science fiction|science fiction
STYLE|哥特|gothic|gothic
STYLE|极简|minimalist|minimalism
STYLE|波普艺术|pop art|pop art
STYLE|装饰艺术|art deco|art deco
STYLE|新艺术运动|art nouveau|art nouveau
STYLE|印象派|impressionism|impressionism
STYLE|超现实|surrealism|surreal
STYLE|概念艺术|concept art|concept art
STYLE|绘本|children's book illustration|storybook
STYLE|Q 版|chibi|chibi
STYLE|黏土|claymation|clay
STYLE|低多边形|low poly|low poly
STYLE|等距视角|isometric|isometric
STYLE|剪纸|paper cut art|paper cutout
STYLE|彩色玻璃|stained glass|stained glass
STYLE|漫画|manga style, monochrome|manga, monochrome
STYLE|美漫|comic book style|comic
STYLE|游戏 CG|game cg|game cg
STYLE|涂鸦|graffiti|graffiti
STYLE|数字绘画|digital painting|digital painting
PERSON|一群人|a group of people|multiple girls, multiple boys
PERSON|两个女孩|two girls|2girls
PERSON|女人|a woman|1girl, mature female
PERSON|男人|a man|1boy, mature male
PERSON|老人|an elderly person|old
PERSON|小孩|a child|child
PERSON|长发|long hair|long hair
PERSON|短发|short hair|short hair
PERSON|双马尾|twin tails|twintails
PERSON|马尾|ponytail|ponytail
PERSON|丸子头|hair bun|hair bun
PERSON|刘海|bangs|bangs
PERSON|黑发|black hair|black hair
PERSON|金发|blonde hair|blonde hair
PERSON|银发|silver hair|silver hair
PERSON|棕发|brown hair|brown hair
PERSON|粉发|pink hair|pink hair
PERSON|蓝眼睛|blue eyes|blue eyes
PERSON|红眼睛|red eyes|red eyes
PERSON|异色瞳|heterochromia|heterochromia
PERSON|戴眼镜|glasses|glasses
PERSON|雀斑|freckles|freckles
PERSON|大笑|laughing|laughing
PERSON|害羞|blushing, shy|blush
PERSON|哭泣|crying, tears|crying, tears
PERSON|生气|angry|angry
PERSON|惊讶|surprised|surprised
PERSON|闭眼|eyes closed|closed eyes
PERSON|眨眼|winking|one eye closed
PERSON|面无表情|expressionless|expressionless
PERSON|站立|standing|standing
PERSON|坐着|sitting|sitting
PERSON|躺着|lying down|lying
PERSON|奔跑|running|running
PERSON|行走|walking|walking
PERSON|跳跃|jumping|jumping
PERSON|回眸|looking back|looking back
PERSON|看向远方|looking away|looking away
PERSON|伸手|reaching out|reaching
PERSON|比耶|peace sign|v
PERSON|抱膝|hugging knees|hugging own legs
PERSON|托腮|chin rest|hand on own chin
PERSON|双手背后|hands behind back|arms behind back
PERSON|校服|school uniform|school uniform
PERSON|西装|suit|suit
PERSON|连衣裙|dress|dress
PERSON|汉服|hanfu|hanfu
PERSON|和服|kimono|kimono
PERSON|旗袍|qipao|china dress
PERSON|卫衣|hoodie|hoodie
PERSON|衬衫|shirt|shirt
PERSON|毛衣|sweater|sweater
PERSON|大衣|coat|coat
PERSON|夹克|jacket|jacket
PERSON|短裙|skirt|skirt
PERSON|牛仔裤|jeans|jeans
PERSON|围巾|scarf|scarf
PERSON|帽子|hat|hat
PERSON|铠甲|armor|armor
PERSON|女仆装|maid outfit|maid
PERSON|运动服|sportswear|sportswear
PERSON|泳装|swimsuit|swimsuit
PERSON|睡衣|pajamas|pajamas
PERSON|赤脚|barefoot|barefoot
PERSON|猫耳|cat ears|cat ears
PERSON|翅膀|wings|wings
SUBJECT|狗|a dog|dog
SUBJECT|鸟|a bird|bird
SUBJECT|狐狸|a fox|fox
SUBJECT|兔子|a rabbit|rabbit
SUBJECT|马|a horse|horse
SUBJECT|鱼|fish|fish
SUBJECT|蝴蝶|a butterfly|butterfly
SUBJECT|龙|a dragon|dragon
SUBJECT|机器人|a robot|robot
SUBJECT|机甲|mecha|mecha
SUBJECT|汽车|a car|car
SUBJECT|摩托车|a motorcycle|motorcycle
SUBJECT|自行车|a bicycle|bicycle
SUBJECT|火车|a train|train
SUBJECT|飞船|a spaceship|spacecraft
SUBJECT|城堡|a castle|castle
SUBJECT|房子|a house|house
SUBJECT|花|flowers|flower
SUBJECT|樱花|cherry blossoms|cherry blossoms
SUBJECT|玫瑰|roses|rose
SUBJECT|向日葵|sunflowers|sunflower
SUBJECT|树|a tree|tree
SUBJECT|食物|food|food
SUBJECT|蛋糕|cake|cake
SUBJECT|咖啡|a cup of coffee|coffee
SUBJECT|书|books|book
SUBJECT|雨伞|an umbrella|umbrella
SUBJECT|剑|a sword|sword
SUBJECT|吉他|a guitar|guitar
SUBJECT|灯笼|lanterns|lantern
SUBJECT|气球|balloons|balloon
SUBJECT|静物|still life|still life
SCENE|室内|indoors|indoors
SCENE|室外|outdoors|outdoors
SCENE|卧室|bedroom|bedroom
SCENE|教室|classroom|classroom
SCENE|咖啡馆|cafe|cafe
SCENE|图书馆|library|library
SCENE|厨房|kitchen|kitchen
SCENE|办公室|office|office
SCENE|街道|street|street
SCENE|小巷|alley|alley
SCENE|天台|rooftop|rooftop
SCENE|车站|train station|train station
SCENE|公园|park|park
SCENE|花园|garden|garden
SCENE|花田|flower field|flower field
SCENE|森林|forest|forest
SCENE|草原|grassland|grass, field
SCENE|山脉|mountains|mountain
SCENE|雪山|snowy mountains|snow, mountain
SCENE|湖泊|lake|lake
SCENE|河流|river|river
SCENE|瀑布|waterfall|waterfall
SCENE|海边|beach|beach
SCENE|大海|ocean|ocean
SCENE|水下|underwater|underwater
SCENE|沙漠|desert|desert
SCENE|洞穴|cave|cave
SCENE|废墟|ruins|ruins
SCENE|神社|shrine|shrine
SCENE|古镇|ancient town|east asian architecture
SCENE|未来都市|futuristic city|cityscape, science fiction
SCENE|城市夜景|city at night|city lights, night
SCENE|太空|outer space|space
SCENE|星空|starry sky|starry sky
SCENE|云海|sea of clouds|cloud, above clouds
SCENE|下雨|rain|rain
SCENE|下雪|snowing|snowing
SCENE|雾|fog|fog
SCENE|春天|spring|spring (season)
SCENE|夏天|summer|summer
SCENE|秋天|autumn, fallen leaves|autumn, autumn leaves
SCENE|冬天|winter|winter
SCENE|白色背景|white background|white background, simple background
SCENE|简单背景|simple background|simple background
COMPOSE|大特写|extreme close-up|portrait, close-up
COMPOSE|肖像|portrait|portrait
COMPOSE|牛仔镜头|cowboy shot|cowboy shot
COMPOSE|远景|long shot|very wide shot
COMPOSE|仰视|from below, low angle|from below
COMPOSE|侧面|from side, profile|from side, profile
COMPOSE|背影|from behind|from behind
COMPOSE|鸟瞰|aerial view|aerial view
COMPOSE|荷兰角|dutch angle|dutch angle
COMPOSE|对称构图|symmetrical composition|symmetry
COMPOSE|三分法|rule of thirds|rule of thirds
COMPOSE|居中构图|centered composition|centered
COMPOSE|动态姿势|dynamic pose|dynamic pose
COMPOSE|鱼眼|fisheye lens|fisheye
COMPOSE|微距|macro photography|macro
COMPOSE|长焦|telephoto lens|telephoto
COMPOSE|35mm 镜头|35mm lens|35mm
COMPOSE|85mm 人像|85mm portrait lens|85mm
COMPOSE|背景虚化|bokeh|bokeh
COMPOSE|动态模糊|motion blur|motion blur
COMPOSE|第一人称|first-person view|pov
COMPOSE|剪影|silhouette|silhouette
COMPOSE|倒影|reflection|reflection
COMPOSE|全景|panorama|panorama
COMPOSE|分镜|multiple views|multiple views
LIGHT|自然光|natural lighting|natural light
LIGHT|阳光|sunlight|sunlight
LIGHT|丁达尔光|god rays, volumetric light|light rays
LIGHT|体积光|volumetric lighting|volumetric lighting
LIGHT|边缘光|rim lighting|rim light
LIGHT|侧光|side lighting|side lighting
LIGHT|顶光|top lighting|overhead lighting
LIGHT|伦勃朗光|rembrandt lighting|chiaroscuro
LIGHT|影棚光|studio lighting|studio lighting
LIGHT|柔和漫射光|diffused light|diffused light
LIGHT|硬光|hard light, strong shadows|hard shadow
LIGHT|蓝调时刻|blue hour|blue hour
LIGHT|日出|sunrise|sunrise
LIGHT|黄昏|dusk|dusk, twilight
LIGHT|月光|moonlight|moonlight
LIGHT|烛光|candlelight|candlelight
LIGHT|篝火光|firelight|campfire
LIGHT|发光|glowing|glowing
LIGHT|荧光|bioluminescence|bioluminescence
LIGHT|镜头光晕|lens flare|lens flare
LIGHT|光斑|light particles|light particles
LIGHT|阴天|overcast|overcast
LIGHT|暗调|low key lighting|dark
LIGHT|亮调|high key lighting|bright
COLOR|暖色调|warm tones|warm colors
COLOR|冷色调|cool tones|cool colors
COLOR|高饱和|vibrant colors|vibrant colors
COLOR|低饱和|muted colors|muted color
COLOR|莫兰迪色|muted pastel palette|pastel colors
COLOR|马卡龙色|pastel colors|pastel colors, colorful
COLOR|黑白|black and white|monochrome, greyscale
COLOR|单色|monochrome|monochrome
COLOR|复古色调|vintage color grading|faded
COLOR|赛博霓虹色|neon color palette|neon palette
COLOR|金色|golden palette|gold theme
COLOR|蓝色主调|blue theme|blue theme
COLOR|红色主调|red theme|red theme
COLOR|绿色主调|green theme|green theme
COLOR|紫色主调|purple theme|purple theme
COLOR|梦幻|dreamy, ethereal|dreamy
COLOR|温馨|cozy atmosphere|cozy
COLOR|宁静|serene, peaceful|peaceful
COLOR|忧郁|melancholic mood|melancholy
COLOR|神秘|mysterious atmosphere|mysterious
COLOR|史诗|epic atmosphere|epic
COLOR|恐怖|horror atmosphere|horror (theme)
COLOR|清新|fresh and clean|fresh
NEGATIVE|畸形|deformed, disfigured|deformed
NEGATIVE|解剖错误|bad anatomy|bad anatomy
NEGATIVE|比例错误|bad proportions|bad proportions
NEGATIVE|多余肢体|extra limbs|extra limbs, extra arms
NEGATIVE|缺失肢体|missing limbs|missing limb
NEGATIVE|手指融合|fused fingers|fused fingers
NEGATIVE|脸崩|bad face, ugly face|bad face
NEGATIVE|眼睛错位|asymmetrical eyes|asymmetrical eyes
NEGATIVE|裁切|cropped, out of frame|cropped, out of frame
NEGATIVE|过曝|overexposed|overexposed
NEGATIVE|欠曝|underexposed|underexposed
NEGATIVE|噪点|noisy, grainy|noise
NEGATIVE|低分辨率|low resolution|lowres
NEGATIVE|签名|signature|signature
NEGATIVE|Logo|logo|logo
NEGATIVE|边框|border, frame|border
NEGATIVE|重复|duplicate|duplicate
NEGATIVE|丑陋|ugly|ugly
NEGATIVE|草图感|sketchy, unfinished|sketch
NEGATIVE|塑料感|plastic skin, cgi|plastic
NEGATIVE|色带|color banding|banding
NEGATIVE|过饱和|oversaturated|oversaturated
NEGATIVE|对话框|speech bubble|speech bubble
"""

    fun parse(table: String = TABLE): List<PromptTag> = table.lineSequence().mapNotNull { line ->
        val p = line.split('|')
        if (p.size < 3 || p[2].isBlank()) null
        else PromptTag(TagCat.of(p[0].trim()), p[1].trim(), p[2].trim(), p.getOrNull(3)?.trim()?.ifEmpty { null })
    }.toList()
}
