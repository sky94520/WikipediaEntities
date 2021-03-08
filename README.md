维基百科实体和同义词
===============================
**从维基百科派生命名实体和同义词。**
该项目维护自[WikipediaEntities](https://github.com/kno10/WikipediaEntities)  
##1. 修正BUG
>①该项目引用的Lucene 库版本过低导致的错误，升级版本5.3.1到7.4.0 解决  
>②繁体中文zhwiki-latest-pages-articles.xml.bz2，压缩包越1.95G，当读取该文件时，抛出异常“javax.xml.stream.XMLStreamException”，该异常触发原因在于java xml解析器带有XML限制，由于该文件行数不超过4200w，所以在使用解析器前调用图4-11所示的代码，取消该限制：
>```
>   final XMLInputFactory factory = XMLInputFactory.newInstance();
>   // 设置entity size , 否则会报 JAXP00010004 错误，但是仍然会在4200w行左右出错
>   factory.setProperty("http://www.oracle.com/xml/jaxp/properties/totalEntitySizeLimit", Integer.MAX_VALUE);
>```
##2. 数据源
> 本文仅仅针对中文，包括[维基百科繁体中文数据源](https://dumps.wikimedia.org/zhwiki/latest/zhwiki-latest-pages-articles.xml.bz2)
> 和[WikiData](https://dumps.wikimedia.org/wikidatawiki/entities/)  
> 从WikiData文件提取数据主要使用```com.github.kno10.wikipediaentities.LoadWikiData```类，
> 该类以流式的形式读取并解析JSON格式的压缩包，
> 以CSV的文件格式保存主题的id和标题到```wikidata.tsv.gz```，
> 格式如下：
> ```
> WikiDataID	zhwiki
> Q31	比利时
> Q8	幸福
> Q23	乔治·华盛顿
> Q24	傑克·鮑爾
> Q42	道格拉斯·亚当斯
> Q1868	保罗·奥特莱
> Q2013	维基数据
> Q45	葡萄牙
> Q51	南极洲
> ...
> ```
> 示例仅仅包含中文，若要更改语言，请修改LoadData的main函数的语言:
> ```
>public static void main(String[] args) {
>   try {
>       new LoadWikiData().load("wikidata-latest-all.json.bz2", "zhwiki"); //zhwiki代表中文
>   }
>       catch(IOException e) {
>       e.printStackTrace();
>   }
>}
> ```
> 之后先使用ParseWikipedia解析维基百科数据并得到redirect.gz、link.gz、linktext.gz，
> 然后输入上述4个文件，使用AnalyzeLinks类处理并生成entities，格式如下：
> ```
> 007	9130	6369	Q844:占士邦:1049:459:72%
> ```
## 3. 流程
>第一步，解析维基百科原始语料pages-articles.xml.bz2的完整备份，从每个文章页面提取：
>>①重定向目标，如果文章是重定向的，提取重定向关系并保存在redirects.gz文件；  
>>②解析并保存词条以及该词条页面中的所有自由链接到links.gz文件；  
>>③保存所有页面的自由链接，分词后保存在linktext.gz文件；  
>>④采用lucene建立索引，保存词条、词条的自由链接和用于搜索的文章全文。  
>第二步，解析WikiData中的词条数据，并保存为wikidata.tsv.gz。Wikidata是维基媒体基金会主持的一个自由的协作式多语言辅助知识库，旨在为维基百科、维基共享资源以及其他的维基媒体项目提供支持，每个文档都有一个主题或一个管理页面，且被唯一的数字标识，它对应了不同语言的相同含义的主题，旨在消除语言之间的差别。本文仅仅使用了中文语料。
>第三步，读取redirects.gz和links.gz，然后遍历linktext.gz，对于linktexts.gz中的每个自由链接短语，查询Lucene数据库中出现这个短语的页面，通过计算得到entities。
> 以“Obamacare”这个短语为例，维基百科上有251篇文章使用了这个短语。对于每个文章，解析自由链接和重定向。比如，“Ralph Hudgens”这篇文章的自由链接之一就是“Obamacare”，“Obamacare”重定向到“Patient Protection and Affordable Care Act”。因此，我们认为这篇文章支持"Obamacare"是一个实体，而这个实体的维基百科名称就是最终目标。
## 4. 维基百科页面结构
> 维基百科主要分为两种页面，第一种是重定向页面，第二种为词条页面，重定向页面如下：
> ```
> #REDIRECT [[历史]]{{簡繁重定向}}
> ```
> 当访问“歷史”这个词条时，该页面会自动跳转到“历史”，其跳转原因为简繁重定向，其他重定向还有拼写重定向、全名重定向、别名重定向等。第二种是详细页面，比如“历史”词条的页面结构如下:
> ```
><page>
> <title>历史</title>
> <id>22</id>
> <timestamp> 2020-12-30T22:45:36Z </timestamp>
> <username>InternetArchiveBot </username>
> <comment>补救7个来源，并将0个来源标记为失效。) #IABot (v2.0.7 </comment>
> <text xml:space="preserve">
>	'''歷史'''（现代汉语词汇，古典文言文称之为'''史'''），指[[人类社会]][[过去]]的事件和行动…
> </text>
></page>
> ```
> “[[ ]]”结构的为自由链接，自由链接是链接到Wiki页面的词条，
> 在“历史”页面中，“人类社会”、“过去”等链接为自由链接，
> 点击会直接跳转到相应的维基百科词条页面。
> 一般情况下，重定向表示这两个词具有强关系，比如同义词；
> 自由链接则表示这两个粗具有弱关系，比如“历史”和“人类社会”、“过去”。
> WikipediaEntities处理重定向页面生成redirects.gz，使用详情页面的
> 自由链接对应的词条分字生成linktext.gz，自由链接的关系生成links.gz
