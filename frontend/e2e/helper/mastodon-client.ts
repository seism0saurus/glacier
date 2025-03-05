import * as path from 'path';
import * as fs from 'fs';

// WARNING: Only do this in a development environment with local mastodon.
// Never disable certificate checking in productive environments.
process.env['NODE_TLS_REJECT_UNAUTHORIZED'] = '0';

const url = process.env['MASTODON_USER_API_URL'] || 'https://proxy';
const host = process.env['MASTODON_USER_API_HOSTNAME'] || 'proxy';
const accessToken = process.env['MASTODON_USER_ACCESS_TOKEN'] || 'pyPuRhw4cZJHN4QJuMX8mo9CFmziZp_BjvuCf71sV34';

console.info(`Target for mastodon test user is url: ${url}, host: ${host}`);

export async function createTextToot(
  text: string = 'Hi Glacier.\nThis is a test toot.',
  visibility: string = 'public'
): Promise<void> {
  try {
    const endpoint = `${url}/api/v1/statuses`;

    const response = await fetch(endpoint, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${accessToken}`,
        'Content-Type': 'application/json',
        'Host': host
      },
      body: JSON.stringify({
        status: text,
        visibility: visibility
      }),
    });

    if (!response.ok) {
      console.error(`Couldn't create status: ${response.status} ${response.statusText}`);
    }
    const data = await response.json();
    // console.log('Toot created successfully:', data);
  } catch (error) {
    console.error('Error during creation of status:', error);
  }
}

export async function createMediaToot(
  text: string = 'Hi Glacier.\nThis is a test toot.',
  mediaPath: string = 'mastodon.jpeg',
  description: string = 'A cute mastodon in front of a glacier',
  visibility: string = 'public'
): Promise<void> {

  const filePath = path.resolve(__dirname, mediaPath);

  if (!fs.existsSync(filePath)) {
    console.error('Media file does not exists:', filePath);
  }

  try {
    const mediaId = await uploadMedia(filePath, description);

    const endpoint = `${url}/api/v1/statuses`;

    const response = await fetch(endpoint, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${accessToken}`,
        'Content-Type': 'application/json',
        'Host': host
      },
      body: JSON.stringify({
        status: text,
        media_ids: [mediaId],
        visibility: visibility
      })
    });

    if (!response.ok) {
      console.error(`Couldn't create status: ${response.status} ${response.statusText}`);
    }

    const data = await response.json();
    // console.log('Toot created successfully:', data);
  } catch (error) {
    console.error('Error during creation of status:', error);
  }
}

async function uploadMedia(filePath: string, description: string): Promise<string> {
  try {
    const endpoint = `${url}/api/v2/media`;

    const file = fs.readFileSync(filePath);

    const formData = new FormData();
    formData.append('file', new Blob([file]), filePath);
    formData.append('description', description);

    const response = await fetch(endpoint, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${accessToken}`,
        'Host': host
      },
      body: formData
    });

    if (!response.ok) {
      console.error(`Couldn't upload media: ${response.status} ${response.statusText}`);
    }

    const data = await response.json();
    return data.id;
  } catch (error) {
    console.error('Error during media upload:', error);
    throw error;
  }
}

