def call(Map config = [:]) {
  def volumeName = config.volumeName ?: "ws"
  def mountPoint = config.mountPoint ?: pwd
  def imageName = config.imageName ?: "workspace_${volumeName}.sparsebundle"
  def imageBasePath = config.imageBasePath

  if(!imageBasePath) {
    error "provide path to base directory of image"
  }

  ws(imageBasePath) {
    if(!fileExists("${imageName}")) {
      sh script: "hdiutil create -library SPUD -size 500g -fs APFS -type SPARSEBUNDLE -nospotlight -volname ${volumeName} ${imageName}", label: 'create ws image', returnStdout: true
    }
  }

  if(!fileExists("${mountPoint}")) {
    sh script: "mkdir -p ${mountPoint}", label: 'create mount point'
  }
 
  sh script: "hdiutil attach -mountpoint ${mountPoint} -nobrowse ${imageBasePath}/${imageName}", label: 'mount ws image'
}