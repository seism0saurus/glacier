const url = process.env['MASTODON_USER_API_URL'] || '';
const accessToken = process.env['MASTODON_USER_ACCESS_TOKEN'] || '';

export async function createTextToot(text: string = 'Hi Glacier.\nThis is a test toot.'): Promise<void> {
  try {
    const endpoint = `${url}/api/v1/statuses`;

    const response = await fetch(endpoint, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${accessToken}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        status: text,
        visibility: 'public'
      })
    });

    if (!response.ok) {
      throw new Error(`Couldn't create status: ${response.status} ${response.statusText}`);
    }
    await response.json();
  } catch (error) {
    console.error('Error during creation of status:', error);
  }
}
