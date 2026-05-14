// Site sandbox path (after copy): config/studio/scripts/aiassistant/user-tools/hello.groovy
// Invoked via Spring AI tool InvokeSiteUserTool with toolId "hello" and optional args.name

def name = args?.name?.toString()?.trim()
if (!name) {
  name = 'author'
}

[
  ok       : true,
  message  : "Hello, ${name}!",
  siteId   : siteId,
  hint     : 'Use studio.getContent(siteId, path) and other StudioToolOperations helpers from real tools.'
]
