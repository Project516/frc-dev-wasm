// Unpacking the offline API documentation needs a zip reader the extension does not carry
export default async function extract() {
  throw new Error('Offline API documentation cannot be unpacked in the browser')
}
