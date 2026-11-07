// Download public-domain NASA (Webb / Hubble / Spitzer) nebula photos and push them to Starshine's "nebulae" folder on the pod.
// They then appear in Backgrounds → Starfield → Nebula backdrop.
const POD = require("./config").pod   // the pod's adb address, saved by Connect (other adb devices may exist, so always target it)
const adb = (process.platform === "win32" ? "" : "./") + "adb"
const DEST = "/sdcard/Android/data/com.starshine.launcher/files/nebulae/"
const PHOTOS = [
  ["Carina_Nebula_Webb.jpg", "https://images-assets.nasa.gov/image/carina_nebula/carina_nebula~large.jpg"],
  ["Pillars_of_Creation_Webb.jpg", "https://images-assets.nasa.gov/image/PIA25433/PIA25433~large.jpg"],
  ["Orion_Nebula.jpg", "https://images-assets.nasa.gov/image/PIA25434/PIA25434~large.jpg"],
  ["Tarantula_Nebula.jpg", "https://images-assets.nasa.gov/image/PIA14415/PIA14415~large.jpg"],
  ["Lagoon_Nebula_Hubble.jpg", "https://images-assets.nasa.gov/image/GSFC_20171208_Archive_e001955/GSFC_20171208_Archive_e001955~large.jpg"]
]
module.exports = {
  run: [
    ...PHOTOS.map(([name, url]) => ({ method: "fs.download", params: { uri: url, path: `nebulae/${name}` } })),
    {
      method: "shell.run",
      params: {
        path: "tools/scrcpy", env: { ANDROID_SERIAL: POD }, conda: { skip: true },
        on: [{ event: "/error:/i", break: false }],
        message: [
          `${adb} shell mkdir -p ${DEST}`,
          ...PHOTOS.map(([name]) => `${adb} push "../../nebulae/${name}" ${DEST}`),
          `${adb} shell ls -l ${DEST}`
        ]
      }
    },
    { method: "notify", params: { html: "5 NASA nebula photos are on the pod: Backgrounds → Starfield → Nebula backdrop." } }
  ]
}
